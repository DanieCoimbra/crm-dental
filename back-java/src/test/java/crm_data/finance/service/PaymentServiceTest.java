package crm_data.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.clinic.service.ClinicZones;
import crm_data.finance.domain.InstallmentStatus;
import crm_data.finance.domain.Payment;
import crm_data.finance.domain.PaymentKind;
import crm_data.finance.domain.PaymentMethod;
import crm_data.finance.repository.InstallmentRepository;
import crm_data.finance.repository.InstallmentRow;
import crm_data.finance.repository.PaymentRepository;
import crm_data.finance.service.PaymentService.PaymentResult;
import crm_data.patient.service.PatientLookup;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

class PaymentServiceTest {

	private final PaymentRepository payments = mock(PaymentRepository.class);
	private final InstallmentRepository installments = mock(InstallmentRepository.class);
	private final PatientLookup patients = mock(PatientLookup.class);
	private final ClinicZones zones = mock(ClinicZones.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	// 2026-10-27 01:30 UTC = 2026-10-26 22:30 em São Paulo
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T01:30:00Z"), ZoneOffset.UTC);
	private final PaymentService service = new PaymentService(payments, installments, patients, zones, clock, events, entityManager);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID installmentId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final InstallmentRow row = new InstallmentRow(installmentId, patientId, UUID.randomUUID(), 1, InstallmentStatus.OPEN,
			new BigDecimal("400.00"), LocalDate.parse("2026-12-01"), 3L);

	@BeforeEach
	void setUp() {
		TenantContext.set(clinicId, userId, Role.DENTIST_ADMIN);
		when(zones.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
		when(installments.findRowById(installmentId)).thenReturn(Optional.of(row));
		when(payments.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
		when(payments.findOwnById(any())).thenAnswer(call -> Optional.empty());
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private static Payment stored(UUID installment, PaymentKind kind) {
		Payment payment = Payment.payment(installment, new BigDecimal("150.00"), PaymentMethod.PIX, LocalDate.parse("2026-10-20"), UUID.randomUUID());
		ReflectionTestUtils.setField(payment, "kind", kind);
		return payment;
	}

	private void foundAfterSave() {
		when(payments.save(any(Payment.class))).thenAnswer(call -> {
			Payment saved = call.getArgument(0);
			when(payments.findOwnById(saved.getId())).thenReturn(Optional.of(saved));
			return saved;
		});
	}

	@Test
	void registerPersistsAPaymentDatedTodayInTheClinicZoneAndFlushesBeforeRereading() {
		foundAfterSave();

		PaymentResult result = service.register(installmentId, new BigDecimal("150.00"), PaymentMethod.PIX, null);

		ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
		verify(payments).save(saved.capture());
		assertThat(saved.getValue().getPaidOn()).isEqualTo(LocalDate.parse("2026-10-26")); // data da clínica, não a do servidor (UTC)
		assertThat(saved.getValue().getKind()).isEqualTo(PaymentKind.PAYMENT);
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(userId);
		assertThat(saved.getValue().getReversesPaymentId()).isNull();
		InOrder order = inOrder(entityManager, installments);
		order.verify(entityManager).flush();
		order.verify(entityManager).clear();
		assertThat(result.installment()).isEqualTo(row);
		assertThat(result.netPaid()).isEqualByComparingTo("0.00");
		verify(events).publishEvent(new FinanceEvents.PaymentRegistered(clinicId, saved.getValue().getId(), installmentId, userId));
	}

	@Test
	void aFutureDateIs422WithTheStableReasonAndNothingIsSaved() {
		assertThatThrownBy(() -> service.register(installmentId, new BigDecimal("10.00"), PaymentMethod.CASH, LocalDate.parse("2026-10-27")))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.BUSINESS_RULE);
					assertThat(ex.reason()).isEqualTo("FUTURE_DATE");
				});

		verify(payments, never()).save(any());
	}

	@Test
	void todayInTheClinicAndAPastDateAreAccepted() {
		foundAfterSave();

		service.register(installmentId, new BigDecimal("10.00"), PaymentMethod.CASH, LocalDate.parse("2026-10-26"));
		service.register(installmentId, new BigDecimal("10.00"), PaymentMethod.CASH, LocalDate.parse("2020-01-01"));

		verify(payments, org.mockito.Mockito.times(2)).save(any());
	}

	@Test
	void anUnknownInstallmentIs404AndAnAnonymizedPatientIs409() {
		UUID unknown = UUID.randomUUID();
		when(installments.findRowById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.register(unknown, BigDecimal.TEN, PaymentMethod.PIX, null)).isInstanceOf(BusinessException.class)
				.hasMessageContaining("não encontrada");
		doThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.")).when(patients).requireWritable(patientId);
		assertThatThrownBy(() -> service.register(installmentId, BigDecimal.TEN, PaymentMethod.PIX, null)).isInstanceOf(BusinessException.class);

		verify(payments, never()).save(any());
	}

	@Test
	void reverseCreatesATotalReversalOfTheSameInstallmentAmountAndMethodDatedToday() {
		Payment original = stored(installmentId, PaymentKind.PAYMENT);
		when(payments.findOwnById(original.getId())).thenReturn(Optional.of(original));
		when(payments.existsByReversesPaymentId(original.getId())).thenReturn(false);
		when(payments.save(any(Payment.class))).thenAnswer(call -> {
			Payment saved = call.getArgument(0);
			when(payments.findOwnById(saved.getId())).thenReturn(Optional.of(saved));
			return saved;
		});

		service.reverse(original.getId());

		ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
		verify(payments).save(saved.capture());
		Payment reversal = saved.getValue();
		assertThat(reversal.getKind()).isEqualTo(PaymentKind.REVERSAL);
		assertThat(reversal.getReversesPaymentId()).isEqualTo(original.getId());
		assertThat(reversal.getInstallmentId()).isEqualTo(installmentId);
		assertThat(reversal.getAmount()).isEqualByComparingTo("150.00");
		assertThat(reversal.getMethod()).isEqualTo(PaymentMethod.PIX);
		assertThat(reversal.getPaidOn()).isEqualTo(LocalDate.parse("2026-10-26"));
		verify(events).publishEvent(new FinanceEvents.PaymentReversed(clinicId, reversal.getId(), original.getId(), installmentId, userId));
	}

	@Test
	void reversingAReversalOrAReversedPaymentOrAnUnknownOneIsRefused() {
		Payment reversal = stored(installmentId, PaymentKind.REVERSAL);
		Payment reversed = stored(installmentId, PaymentKind.PAYMENT);
		when(payments.findOwnById(reversal.getId())).thenReturn(Optional.of(reversal));
		when(payments.findOwnById(reversed.getId())).thenReturn(Optional.of(reversed));
		when(payments.existsByReversesPaymentId(reversed.getId())).thenReturn(true);
		UUID unknown = UUID.randomUUID();
		when(payments.findOwnById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.reverse(reversal.getId())).hasMessage(ConflictMessages.REVERSAL_OF_REVERSAL);
		assertThatThrownBy(() -> service.reverse(reversed.getId())).hasMessage(ConflictMessages.PAYMENT_REVERSED);
		assertThatThrownBy(() -> service.reverse(unknown)).isInstanceOf(BusinessException.class).hasMessageContaining("não encontrado");

		verify(payments, never()).save(any());
	}

	@Test
	void listReturnsTheChronologicalHistoryAndChecksTheInstallment() {
		Payment first = stored(installmentId, PaymentKind.PAYMENT);
		when(payments.findByInstallmentIdOrderByCreatedAtAscIdAsc(installmentId)).thenReturn(List.of(first));
		UUID unknown = UUID.randomUUID();
		when(installments.findRowById(unknown)).thenReturn(Optional.empty());

		assertThat(service.list(installmentId)).containsExactly(first);
		assertThatThrownBy(() -> service.list(unknown)).isInstanceOf(BusinessException.class);
	}

	@Test
	void thePaymentSideOfTheCodeNeverUpdatesOrDeletes() throws Exception {
		assertThat(JpaRepositoryCheck.extendsJpaRepository(PaymentRepository.class)).isFalse();
		assertThat(PaymentRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName)
				.noneMatch(name -> name.startsWith("delete") || name.startsWith("remove") || name.startsWith("update")
						|| name.equals("saveAll") || name.equals("flush"));
		assertThat(Payment.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
		assertThat(Payment.class.getMethods()).extracting(java.lang.reflect.Method::getName).noneMatch(name -> name.startsWith("set"));
	}

	/** Pequeno auxiliar: evita importar o tipo só para a verificação estrutural. */
	private static final class JpaRepositoryCheck {

		static boolean extendsJpaRepository(Class<?> type) {
			return org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(type);
		}
	}
}
