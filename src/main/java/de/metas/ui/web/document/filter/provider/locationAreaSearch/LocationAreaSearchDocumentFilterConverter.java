package de.metas.ui.web.document.filter.provider.locationAreaSearch;

import java.util.Optional;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.Adempiere;
import org.compiere.model.I_C_BPartner;
import org.compiere.model.I_C_BPartner_Location;
import org.compiere.model.I_C_Location;

import de.metas.location.CountryId;
import de.metas.location.ICountryDAO;
import de.metas.location.geocoding.GeoCoordinatesProvider;
import de.metas.location.geocoding.GeoCoordinatesRequest;
import de.metas.location.geocoding.GeographicalCoordinates;
import de.metas.ui.web.document.filter.DocumentFilter;
import de.metas.ui.web.document.filter.provider.locationAreaSearch.LocationAreaSearchDescriptor.LocationColumnNameType;
import de.metas.ui.web.document.filter.sql.SqlDocumentFilterConverter;
import de.metas.ui.web.document.filter.sql.SqlDocumentFilterConverterContext;
import de.metas.ui.web.document.filter.sql.SqlParamsCollector;
import de.metas.ui.web.window.model.sql.SqlOptions;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class LocationAreaSearchDocumentFilterConverter implements SqlDocumentFilterConverter
{
	public static final transient LocationAreaSearchDocumentFilterConverter instance = new LocationAreaSearchDocumentFilterConverter();

	public static final String FILTER_ID = "location-area-search";

	public static final String PARAM_LocationAreaSearchDescriptor = "LocationAreaSearchDescriptor";
	public static final String PARAM_Address1 = "Address1";
	public static final String PARAM_Postal = "Postal";
	public static final String PARAM_CountryId = "C_Country_ID";
	public static final String PARAM_Distance = "Distance";

	private LocationAreaSearchDocumentFilterConverter()
	{
	}

	@Override
	public String getSql(
			@NonNull final SqlParamsCollector sqlParamsOut,
			@NonNull final DocumentFilter filter,
			@NonNull final SqlOptions sqlOpts,
			final SqlDocumentFilterConverterContext context_NOTUSED)
	{
		final LocationAreaSearchDescriptor descriptor = filter.getParameterValueAs(PARAM_LocationAreaSearchDescriptor);
		if (descriptor == null)
		{
			// shall not happen
			return null;
		}

		final GeographicalCoordinates addressCoordinates = getAddressCoordinates(filter).orElse(null);
		if (addressCoordinates == null)
		{
			return null;
		}

		final int distanceInKm = extractDistanceInKm(filter);

		if (LocationColumnNameType.LocationId.equals(descriptor.getType()))
		{
			return "EXISTS ("
					+ " SELECT 1"
					+ " FROM " + I_C_Location.Table_Name + " l"
					+ " WHERE "
					+ " l.C_Location_ID=" + sqlOpts.getTableNameOrAlias() + "." + descriptor.getLocationColumnName()
					+ " AND " + sqlGeographicalDistance(sqlParamsOut, "l", addressCoordinates, distanceInKm)
					+ ")";
		}
		else if (LocationColumnNameType.BPartnerLocationId.equals(descriptor.getType()))
		{
			return "EXISTS ("
					+ " SELECT 1"
					+ " FROM " + I_C_BPartner_Location.Table_Name + " bpl"
					+ " INNER JOIN " + I_C_Location.Table_Name + " l ON l.C_Location_ID=bpl.C_Location_ID"
					+ " WHERE "
					+ " bpl.C_BPartner_Location_ID=" + sqlOpts.getTableNameOrAlias() + "." + descriptor.getLocationColumnName()
					+ " AND " + sqlGeographicalDistance(sqlParamsOut, "l", addressCoordinates, distanceInKm)
					+ ")";
		}
		else if (LocationColumnNameType.BPartnerId.equals(descriptor.getType()))
		{
			return "EXISTS ("
					+ " SELECT 1"
					+ " FROM " + I_C_BPartner.Table_Name + " bp"
					+ " INNER JOIN " + I_C_BPartner_Location.Table_Name + " bpl ON bpl.C_BPartner_Location_ID=bp.C_BPartner_Location_ID"
					+ " INNER JOIN " + I_C_Location.Table_Name + " l ON l.C_Location_ID=bpl.C_Location_ID"
					+ " WHERE "
					+ " bp.C_BPartner_ID=" + sqlOpts.getTableNameOrAlias() + "." + descriptor.getLocationColumnName()
					+ " AND bpl.IsActive='Y'"
					+ " AND " + sqlGeographicalDistance(sqlParamsOut, "l", addressCoordinates, distanceInKm)
					+ ")";
		}
		else
		{
			throw new AdempiereException("Unknown " + descriptor.getType());
		}
	}

	private static String sqlGeographicalDistance(
			@NonNull final SqlParamsCollector sqlParamsOut,
			@NonNull final String locationTableAlias,
			@NonNull final GeographicalCoordinates addressCoordinates,
			final int distanceInKm)
	{
		return new StringBuilder()
				.append("geographical_distance(")
				//
				.append(locationTableAlias).append(".").append(I_C_Location.COLUMNNAME_Latitude).append("::real")
				.append(",").append(locationTableAlias).append(".").append(I_C_Location.COLUMNNAME_Longitude).append("::real")
				//
				.append(",").append(sqlParamsOut.placeholder(addressCoordinates.getLatitude())).append("::real")
				.append(",").append(sqlParamsOut.placeholder(addressCoordinates.getLongitude())).append("::real")
				//
				.append(") <= ").append(sqlParamsOut.placeholder(distanceInKm)).append("::real")
				//
				.toString();
	}

	private Optional<GeographicalCoordinates> getAddressCoordinates(final DocumentFilter filter)
	{
		final GeoCoordinatesRequest request = createGeoCoordinatesRequest(filter).orElse(null);
		if (request == null)
		{
			return Optional.empty();
		}

		final GeoCoordinatesProvider geoCoordinatesProvider = Adempiere.getBean(GeoCoordinatesProvider.class);
		return geoCoordinatesProvider.findBestCoordinates(request);
	}

	private static Optional<GeoCoordinatesRequest> createGeoCoordinatesRequest(final DocumentFilter filter)
	{
		final String countryCode2 = extractCountryCode2(filter);
		if (countryCode2 == null)
		{
			return Optional.empty();
		}

		final GeoCoordinatesRequest request = GeoCoordinatesRequest.builder()
				.countryCode2(countryCode2)
				.postal(filter.getParameterValueAsString(PARAM_Postal, null))
				.address(filter.getParameterValueAsString(PARAM_Address1, null))
				.build();

		return Optional.of(request);
	}

	private static String extractCountryCode2(final DocumentFilter filter)
	{
		final CountryId countryId = filter.getParameterValueAsRepoIdOrNull(PARAM_CountryId, CountryId::ofRepoIdOrNull);
		if (countryId == null)
		{
			return null;
		}

		return Services.get(ICountryDAO.class).retrieveCountryCode2ByCountryId(countryId);
	}

	private static int extractDistanceInKm(final DocumentFilter filter)
	{
		final int distanceInKm = filter.getParameterValueAsInt(PARAM_Distance, -1);
		return distanceInKm > 0 ? distanceInKm : 0;
	}
}
