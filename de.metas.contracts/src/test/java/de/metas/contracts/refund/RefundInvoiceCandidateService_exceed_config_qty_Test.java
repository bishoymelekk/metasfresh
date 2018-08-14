package de.metas.contracts.refund;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.test.AdempiereTestHelper;
import org.junit.Before;
import org.junit.Test;

import de.metas.bpartner.BPartnerId;
import de.metas.contracts.ConditionsId;
import de.metas.contracts.refund.RefundConfig.RefundBase;
import de.metas.contracts.refund.RefundConfig.RefundConfigBuilder;
import de.metas.contracts.refund.RefundConfig.RefundInvoiceType;
import de.metas.contracts.refund.RefundConfig.RefundMode;
import de.metas.contracts.refund.RefundContract.RefundContractBuilder;
import de.metas.invoice.InvoiceSchedule;
import de.metas.invoice.InvoiceSchedule.Frequency;
import de.metas.invoice.InvoiceScheduleRepository;
import de.metas.lang.Percent;
import de.metas.money.CurrencyRepository;
import de.metas.money.Money;
import de.metas.money.MoneyService;
import de.metas.quantity.Quantity;

/*
 * #%L
 * de.metas.contracts
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

public class RefundInvoiceCandidateService_exceed_config_qty_Test
{

	private static final BigDecimal TWENTY = new BigDecimal("20");

	private static final LocalDate NOW = LocalDate.now();

	private static final BigDecimal FIVE = new BigDecimal("5");

	private RefundInvoiceCandidateService refundInvoiceCandidateService;

	private RefundTestTools refundTestTools;

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();

		refundTestTools = new RefundTestTools(); // this also makes sure we have the ILCandHandler and C_DocType needed to create a new refund candidate

		RefundConfigRepository refundConfigRepository = new RefundConfigRepository(new InvoiceScheduleRepository());
		RefundContractRepository refundContractRepository = new RefundContractRepository(refundConfigRepository);
		//
		final RefundInvoiceCandidateFactory refundInvoiceCandidateFactory = new RefundInvoiceCandidateFactory(refundContractRepository);

		final RefundInvoiceCandidateRepository refundInvoiceCandidateRepository = new RefundInvoiceCandidateRepository(
				refundContractRepository,
				refundInvoiceCandidateFactory);
		//
		// invoiceCandidateRepository = new InvoiceCandidateRepository(
		// new AssignmentToRefundCandidateRepository(refundInvoiceCandidateRepository),
		// refundContractRepository);
		//
		final MoneyService moneyService = new MoneyService(new CurrencyRepository());
		//
		//
		refundInvoiceCandidateService = new RefundInvoiceCandidateService(
				refundInvoiceCandidateRepository,
				refundInvoiceCandidateFactory,
				moneyService);
		//
		// final InvoiceScheduleRepository invoiceScheduleRepository = new InvoiceScheduleRepository();
		//
		// invoiceScheduleRepository.save(InvoiceSchedule
		// .builder()
		// .frequency(Frequency.DAILY)
		// .build());

	}

	@Test
	public void addAssignableMoney_qty_exceeds_config_per_scale()
	{
		final RefundInvoiceCandidate refundCandidate = prepareRefundCandidateAndConfigs(RefundMode.PER_INDIVIDUAL_SCALE);

		final AssignableInvoiceCandidate assignableCandidate = refundTestTools.createAssignableCandidateStandlone(TWENTY);
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(TWENTY);// guard

		// invoke the method under test
		final Throwable e = catchThrowable(() -> refundInvoiceCandidateService.addAssignableMoney(refundCandidate, assignableCandidate));

		assertThat(e).isInstanceOf(AdempiereException.class);
		assertThat(e).hasMessageContaining("together they exceed the quantity for candidateToUpdate's refund config");

	}

	@Test
	public void addAssignableMoney_qty_exceeds_config_accumulated()
	{
		final RefundInvoiceCandidate refundCandidate = prepareRefundCandidateAndConfigs(RefundMode.ALL_MAX_SCALE);

		final AssignableInvoiceCandidate assignableCandidate = refundTestTools.createAssignableCandidateStandlone(TWENTY);
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(TWENTY);// guard

		// invoke the method under test
		final Throwable e = catchThrowable(() -> refundInvoiceCandidateService.addAssignableMoney(refundCandidate, assignableCandidate));

		assertThat(e).isNull();
	}

	private RefundInvoiceCandidate prepareRefundCandidateAndConfigs(final RefundMode refundMode)
	{

		final RefundContractBuilder refundContractBuilder = RefundContract.builder()
				.bPartnerId(RefundTestTools.BPARTNER_ID)
				.endDate(NOW.plusDays(10))
				.startDate(NOW.minusDays(1));

		// prepare 3 refundConfigs with minQties 0, 8 and 16
		long minQty = 0;
		final RefundConfigBuilder builder = createAndInitConfigBuilder();
		for (int i = 0; i < 3; i++)
		{
			final RefundConfig refundConfig = builder.refundMode(refundMode)
					.minQty(BigDecimal.valueOf(minQty))
					.build();
			minQty += 8;

			// refundConfigRepository.save(refundConfig);
			refundContractBuilder.refundConfig(refundConfig);
		}

		final RefundContract refundContract = refundContractBuilder.build();

		final RefundInvoiceCandidate refundCandidate = RefundInvoiceCandidate.builder()
				.assignedQuantity(Quantity.of(FIVE, refundTestTools.getUomRecord()))
				.bpartnerId(BPartnerId.ofRepoId(10))
				.invoiceableFrom(NOW)
				.money(Money.of(ONE, refundTestTools.getCurrency().getId()))
				.refundConfig(refundContract.getRefundConfig(FIVE))
				.refundContract(refundContract)
				.build();
		return refundCandidate;
	}

	private RefundConfigBuilder createAndInitConfigBuilder()
	{
		final RefundConfigBuilder refundConfigBuilder = RefundConfig
				.builder()
				.minQty(ZERO)
				.refundBase(RefundBase.PERCENTAGE)
				.percent(Percent.of(TWENTY))
				.conditionsId(ConditionsId.ofRepoId(20))
				.invoiceSchedule(InvoiceSchedule
						.builder()
						.frequency(Frequency.DAILY)
						.build())
				.refundInvoiceType(RefundInvoiceType.INVOICE) // keep in sync with the C_DocType's subType that we set up in the constructor.
				.refundMode(RefundMode.ALL_MAX_SCALE);

		return refundConfigBuilder;
	}
}
