package de.metas.handlingunits.ddorder.api.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryOrderBy;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.warehouse.LocatorId;
import org.adempiere.warehouse.WarehouseId;
import org.adempiere.warehouse.api.IWarehouseBL;
import org.adempiere.warehouse.api.IWarehouseDAO;
import org.adempiere.warehouse.api.impl.WarehouseDAO;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Locator;
import org.compiere.model.I_M_Warehouse;
import org.compiere.util.Env;
import org.eevolution.api.DDOrderLineId;
import org.eevolution.api.IDDOrderDAO;
import org.eevolution.model.I_DD_Order;
import org.eevolution.model.I_DD_OrderLine;
import org.eevolution.model.X_DD_OrderLine;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;

import de.metas.adempiere.gui.search.IHUPackingAware;
import de.metas.adempiere.gui.search.IHUPackingAwareBL;
import de.metas.adempiere.gui.search.impl.DDOrderLineHUPackingAware;
import de.metas.handlingunits.HUPIItemProductId;
import de.metas.handlingunits.IHUAssignmentBL;
import de.metas.handlingunits.IHUQueryBuilder;
import de.metas.handlingunits.IHUStatusBL;
import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.IHandlingUnitsDAO;
import de.metas.handlingunits.ddorder.api.DDOrderLineCreateRequest;
import de.metas.handlingunits.ddorder.api.IHUDDOrderBL;
import de.metas.handlingunits.ddorder.api.IHUDDOrderDAO;
import de.metas.handlingunits.ddorder.api.QuarantineInOutLine;
import de.metas.handlingunits.ddorder.api.impl.HUs2DDOrderProducer.HUToDistribute;
import de.metas.handlingunits.exceptions.HUException;
import de.metas.handlingunits.inout.IHUInOutDAO;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.storage.IHUStorage;
import de.metas.handlingunits.storage.IHUStorageFactory;
import de.metas.i18n.AdMessageKey;
import de.metas.product.IProductBL;
import de.metas.product.ProductId;
import de.metas.uom.IUOMDAO;
import de.metas.util.Services;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

public class HUDDOrderBL implements IHUDDOrderBL
{

	private static final AdMessageKey MSG_HU_for_product = AdMessageKey.of("de.metas.handlingunits.ddorder.api.impl.HUDDOrderBL.NoHu_For_Product");
	
	private final IWarehouseBL warehouseBL = Services.get(IWarehouseBL.class);
	private final IHUPackingAwareBL huPackingAwareBL = Services.get(IHUPackingAwareBL .class);
	private final IProductBL productBL = Services.get(IProductBL.class);
	private final IDDOrderDAO ddOrderDAO = Services.get(IDDOrderDAO.class);
	private final IHandlingUnitsDAO handlingUnitsDAO = Services.get(IHandlingUnitsDAO.class);
	private final IHUStatusBL huStatusBL = Services.get(IHUStatusBL.class);
	private final IQueryBL queryBL = Services.get(IQueryBL.class);

	@Override
	public DDOrderLinesAllocator createMovements()
	{
		return DDOrderLinesAllocator.newInstance();
	}

	@Override
	public void closeLine(final I_DD_OrderLine ddOrderLine)
	{
		ddOrderLine.setIsDelivered_Override(X_DD_OrderLine.ISDELIVERED_OVERRIDE_Yes);
		InterfaceWrapperHelper.save(ddOrderLine);

		final IHUDDOrderDAO huDDOrderDAO = Services.get(IHUDDOrderDAO.class);
		huDDOrderDAO.clearHUsScheduledToMoveList(ddOrderLine);
	}

	@Override
	public void unassignHUs(final I_DD_OrderLine ddOrderLine, final Collection<I_M_HU> hus)
	{
		//
		// Unassign the given HUs from DD_OrderLine
		final IHUAssignmentBL huAssignmentBL = Services.get(IHUAssignmentBL.class);
		huAssignmentBL.unassignHUs(ddOrderLine, hus);

		//
		// Remove those HUs from scheduled to move list (task 08639)
		final IHUDDOrderDAO huDDOrderDAO = Services.get(IHUDDOrderDAO.class);
		huDDOrderDAO.removeFromHUsScheduledToMoveList(ddOrderLine, hus);
	}

	@Override
	public List<I_DD_Order> createQuarantineDDOrderForReceiptLines(final List<QuarantineInOutLine> receiptLines)
	{

		final List<HUToDistribute> husToQuarantine = receiptLines.stream()
				.flatMap(receiptLine -> createHUsToQuarantine(receiptLine).stream())
				.collect(ImmutableList.toImmutableList());

		return createQuarantineDDOrderForHUs(husToQuarantine);

	}

	private List<HUToDistribute> createHUsToQuarantine(final QuarantineInOutLine receiptLine)
	{
		final IHUInOutDAO huInOutDAO = Services.get(IHUInOutDAO.class);
		return huInOutDAO.retrieveHUsForReceiptLineId(receiptLine.getReceiptLineId())
				.stream()
				.map(hu -> HUToDistribute.builder()
						.hu(hu)
						.quarantineLotNo(receiptLine.getLotNumberQuarantine())
						.bpartnerId(receiptLine.getBpartnerId())
						.bpartnerLocationId(receiptLine.getBpartnerLocationId())
						.build())
				.collect(ImmutableList.toImmutableList());
	}

	@Override
	public List<I_DD_Order> createQuarantineDDOrderForHUs(final List<HUToDistribute> husToDistribute)
	{
		final IWarehouseDAO warehouseDAO = Services.get(IWarehouseDAO.class);
		final IWarehouseBL warehouseBL = Services.get(IWarehouseBL.class);

		final I_M_Warehouse quarantineWarehouse = warehouseDAO.retrieveQuarantineWarehouseOrNull();
		if (quarantineWarehouse == null)
		{
			throw new AdempiereException("@" + WarehouseDAO.MSG_M_Warehouse_NoQuarantineWarehouse + "@");
		}

		final I_M_Locator defaultLocator = warehouseBL.getDefaultLocator(quarantineWarehouse);

		final ImmutableSet<Entry<BPartnerAndLocationId, Collection<HUToDistribute>>> entries = husToDistribute
				.stream()
				.collect(ImmutableListMultimap.toImmutableListMultimap(this::extractBPartnerAndLocationId, Function.identity()))
				.asMap()
				.entrySet();

		final ImmutableList.Builder<I_DD_Order> result = ImmutableList.builder();
		for (final Entry<BPartnerAndLocationId, Collection<HUToDistribute>> entry : entries)
		{
			final Optional<I_DD_Order> ddOrder = createDDOrder(entry.getKey(), defaultLocator, entry.getValue());
			ddOrder.ifPresent(result::add);
		}
		return result.build();
	}

	public DDOrderLineId addDDOrderLine(@NonNull final DDOrderLineCreateRequest ddOrderLineCreateRequest)
	{
		final ProductId productId = ddOrderLineCreateRequest.getProductId();
		final HUPIItemProductId mHUPIProductID = ddOrderLineCreateRequest.getMHUPIProductID();
		final BigDecimal qtyEntered = ddOrderLineCreateRequest.getQtyEntered();
		final I_DD_Order ddOrder = ddOrderLineCreateRequest.getDdOrder();

		final de.metas.handlingunits.model.I_DD_OrderLine ddOrderLine =
				InterfaceWrapperHelper.newInstance(de.metas.handlingunits.model.I_DD_OrderLine.class, ddOrder);

		ddOrderLine.setIsInvoiced(false);
		ddOrderLine.setDD_Order_ID(ddOrder.getDD_Order_ID());
		ddOrderLine.setM_Product_ID(productId.getRepoId());

		ddOrderLine.setC_UOM_ID(productBL.getStockUOMId(productId).getRepoId());

		final WarehouseId warehouseIdFrom = WarehouseId.ofRepoId(ddOrder.getM_Warehouse_From_ID());
		final LocatorId locatorFromId = warehouseBL.getDefaultLocatorId(warehouseIdFrom);
		ddOrderLine.setM_Locator_ID(locatorFromId.getRepoId());

		final WarehouseId warehouseToId = WarehouseId.ofRepoId(ddOrder.getM_Warehouse_To_ID());
		final LocatorId locatorToId = warehouseBL.getDefaultLocatorId(warehouseToId);
		ddOrderLine.setM_LocatorTo_ID(locatorToId.getRepoId());

		if (mHUPIProductID != null)
		{
			ddOrderLine.setM_HU_PI_Item_Product_ID(mHUPIProductID.getRepoId());
			ddOrderLine.setQtyEnteredTU(qtyEntered);

			final IHUPackingAware packingAware = new DDOrderLineHUPackingAware(ddOrderLine);

			huPackingAwareBL.setQtyCUFromQtyTU(packingAware, qtyEntered.intValue());
		}
		else
		{
			ddOrderLine.setQtyEntered(qtyEntered);
		}

		ddOrderLine.setQtyOrdered(ddOrderLine.getQtyEntered());

		InterfaceWrapperHelper.saveRecord(ddOrderLine);

		return DDOrderLineId.ofRepoId(ddOrderLine.getDD_OrderLine_ID());
	}

	private Optional<I_DD_Order> createDDOrder(
			@NonNull final BPartnerAndLocationId bpartnerAndLocationId,
			@NonNull final I_M_Locator locatorTo,
			@NonNull final Collection<HUToDistribute> hus)
	{
		return HUs2DDOrderProducer.newProducer()
				.setContext(Env.getCtx())
				.setM_Locator_To(locatorTo)
				.setBpartnerId(bpartnerAndLocationId.getBpartnerId())
				.setBpartnerLocationId(bpartnerAndLocationId.getBpartnerLocationId())
				.setHUs(hus.iterator())
				.process();
	}

	private BPartnerAndLocationId extractBPartnerAndLocationId(final HUToDistribute huToDistribute)
	{
		return BPartnerAndLocationId.builder()
				.bpartnerId(huToDistribute.getBpartnerId())
				.bpartnerLocationId(huToDistribute.getBpartnerLocationId())
				.build();
	}

	@Value
	@Builder
	private static class BPartnerAndLocationId
	{
		int bpartnerId;
		int bpartnerLocationId;
	}

	@Override
	public void processDDOrderLines(@NonNull final I_DD_Order ddOrder)
	{
		final List<I_DD_OrderLine> ddOrderLines = ddOrderDAO.retrieveLines(ddOrder);

		for (final I_DD_OrderLine ddOrderLine : ddOrderLines)
		{
			final List<I_M_HU> hus = retrieveNeededHusToMove(ddOrderLine);
			if (hus.isEmpty())
			{
				final LocatorId locatorId = LocatorId.ofRecordOrNull(ddOrderLine.getM_Locator()); 
				final WarehouseId warehouseId = locatorId.getWarehouseId();

				throw new HUException(MSG_HU_for_product)
						.appendParametersToMessage()
						.setParameter("Product", ddOrderLine.getM_Product())
						.setParameter("Warehouse", warehouseId)
						.setParameter("Locator", locatorId);
			}
			
			processDDOrderLine(ddOrderLine, hus);
		}

	}

	private void processDDOrderLine(@NonNull final I_DD_OrderLine ddOrderLine, @NonNull final List<I_M_HU> hus)
	{
		createMovements()
				.setDDOrderLine(ddOrderLine)
				.allocateHUs(hus)
				.setDoDirectMovements(true)
				.setSkipCompletingDDOrder(true)
				.process();
	}
	
	@Override
	public List<I_M_HU> retrieveAvailableHusToMove(@NonNull final I_DD_OrderLine ddOrderLine, @NonNull final IQueryOrderBy queryOrderBy)
	{
		final IHUQueryBuilder huQueryBuilder = handlingUnitsDAO.createHUQueryBuilder().setOnlyTopLevelHUs();

		final LocatorId locatorId = LocatorId.ofRecordOrNull(ddOrderLine.getM_Locator()); 
		final WarehouseId warehouseId = locatorId.getWarehouseId();
		final ProductId productId = ProductId.ofRepoId(ddOrderLine.getM_Product_ID());
		
		huQueryBuilder.addOnlyInWarehouseId(warehouseId);
		huQueryBuilder.addOnlyInLocatorId(locatorId.getRepoId());
		huQueryBuilder.addOnlyWithProductId(productId);
		huQueryBuilder.addHUStatusesToInclude(huStatusBL.getQtyOnHandStatuses());

		return huQueryBuilder.createQuery()
				.setOrderBy(queryOrderBy)
				.list();
	}
	
	/**
	 * retrieve the Hus needed to move in order to be able to move the dd orderline qty
	 * @param ddOrderLine
	 */
	private List<I_M_HU> retrieveNeededHusToMove(final I_DD_OrderLine ddOrderLine)
	{
		final List<I_M_HU> neededHus = new ArrayList<I_M_HU>();
		
		// Order by
		final IQueryOrderBy queryOrderBy = queryBL.createQueryOrderByBuilder(I_M_HU.class)
				.addColumn(I_M_HU.COLUMNNAME_M_Locator_ID)
				.addColumn(I_M_HU.COLUMN_Created)
				.createQueryOrderBy();
		final List<I_M_HU> hus = retrieveAvailableHusToMove(ddOrderLine, queryOrderBy);
		final IUOMDAO uomDAO = Services.get(IUOMDAO.class);

		final I_C_UOM uom = uomDAO.getById(ddOrderLine.getC_UOM_ID());

		
		final IHUStorageFactory storageFactory = Services.get(IHandlingUnitsBL.class).getStorageFactory();
		
		BigDecimal qty = BigDecimal.ZERO;
		
		for (final I_M_HU hu : hus)
		{
			final ProductId productId = ProductId.ofRepoId(ddOrderLine.getM_Product_ID());
			final IHUStorage storage = storageFactory.getStorage(hu);
			final BigDecimal qtyActual = storage.getQty(productId, uom);
			qty = qty.add(qtyActual);
			if (ddOrderLine.getQtyEntered().compareTo(qty) > 0)
			{
				neededHus.add(hu);
			}
			else 
			{
				break;
			}
		}

		return neededHus;
	}
}
