package de.metas.rest_api.invoicecandidates.request;

import java.util.List;

import io.swagger.annotations.ApiModelProperty;
import lombok.Value;

/*
 * #%L
 * de.metas.business.rest-api
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@Value
public class JsonGetInvoiceCandidatesStatusRequest
{
	@ApiModelProperty(position = 10, required = true, //
			value = "Specifies the invoice candidates to be invoiced.")
	List<JsonInvoiceCandidateReference> invoiceCandidates;
}
