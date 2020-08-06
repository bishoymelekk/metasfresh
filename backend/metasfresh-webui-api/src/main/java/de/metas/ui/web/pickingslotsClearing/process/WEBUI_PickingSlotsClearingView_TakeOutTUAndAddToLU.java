package de.metas.ui.web.pickingslotsClearing.process;

import de.metas.handlingunits.HuId;
import de.metas.handlingunits.allocation.transfer.HUTransformService;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.picking.PickingCandidateService;
import de.metas.handlingunits.storage.EmptyHUListener;
import de.metas.process.IProcessPrecondition;
import de.metas.process.ProcessPreconditionsResolution;
import de.metas.ui.web.handlingunits.HUEditorRow;
import de.metas.ui.web.picking.pickingslot.PickingSlotRow;
import de.metas.util.Check;
import org.adempiere.model.InterfaceWrapperHelper;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2018 metas GmbH
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

public class WEBUI_PickingSlotsClearingView_TakeOutTUAndAddToLU extends PickingSlotsClearingViewBasedProcess implements IProcessPrecondition
{
	@Autowired
	private PickingCandidateService pickingCandidateService;

	@Override
	protected ProcessPreconditionsResolution checkPreconditionsApplicable()
	{
		//
		// Validate the picking slots clearing selected row (left side)
		if (!isSingleSelectedPickingSlotRow())
		{
			return ProcessPreconditionsResolution.rejectWithInternalReason("select one and only one picking slots HU");
		}
		final PickingSlotRow pickingSlotRow = getSingleSelectedPickingSlotRow();

		final boolean pickingSlotWithTUs = pickingSlotRow.isPickingSlotRow() && pickingSlotRow.getIncludedRows().stream().anyMatch(PickingSlotRow::isTU);

		if (!pickingSlotRow.isTU() && !pickingSlotWithTUs)
		{
			return ProcessPreconditionsResolution.rejectWithInternalReason("select a TU or a picking slot containing at least one TU!");
		}

		//
		// Validate the packing HUs selected row (right side)
		if (!isSingleSelectedPackingHUsRow())
		{
			return ProcessPreconditionsResolution.rejectWithInternalReason("select one and only one HU to pack to");
		}
		final HUEditorRow packingHURow = getSingleSelectedPackingHUsRow();
		if (!packingHURow.isLU())
		{
			return ProcessPreconditionsResolution.rejectWithInternalReason("select a LU to pack too");
		}

		//
		return ProcessPreconditionsResolution.accept();
	}

	@Override
	protected String doIt() throws Exception
	{
		final PickingSlotRow pickingSlotRow = getSingleSelectedPickingSlotRow();

		Check.assume(pickingSlotRow.isTU() || pickingSlotRow.isPickingSlotRow(), "The selected row should be a picking slot or a TU : {}", pickingSlotRow);

		if (pickingSlotRow.isTU())
		{
			addToLU(pickingSlotRow);
		}
		else
		{
			pickingSlotRow.getIncludedRows()
					.stream()
					.filter(PickingSlotRow::isTU)
					.forEach(this::addToLU);
		}

		return MSG_OK;
	}

	private void addToLU(final PickingSlotRow pickingSlotRow)
	{
		Check.assume(pickingSlotRow.isTU(), "Picking slot HU shall be a TU: {}", pickingSlotRow);
		final I_M_HU tuHU = InterfaceWrapperHelper.load(pickingSlotRow.getHuId(), I_M_HU.class);

		final HUEditorRow packingHURow = getSingleSelectedPackingHUsRow();
		Check.assume(packingHURow.isLU(), "Pack to HU shall be a LU: {}", packingHURow);
		final I_M_HU luHU = packingHURow.getM_HU();

		final BigDecimal qtyTU = BigDecimal.ONE;

		final List<Integer> huIdsDestroyedCollector = new ArrayList<>();

		HUTransformService.builder()
				.emptyHUListener(EmptyHUListener.doBeforeDestroyed(hu -> huIdsDestroyedCollector.add(hu.getM_HU_ID())))
				.build()
				.tuToExistingLU(tuHU, qtyTU, luHU);

		// Remove from picking slots all destroyed HUs
		pickingCandidateService.inactivateForHUIds(HuId.fromRepoIds(huIdsDestroyedCollector));

	}

	@Override
	protected void postProcess(final boolean success)
	{
		if (!success)
		{
			return;
		}

		// Invalidate views
		getPickingSlotsClearingView().invalidateAll();
		getPackingHUsView().invalidateAll();
	}
}
