package crm_data.dashboard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.commercial.service.BudgetLookup;
import crm_data.dashboard.service.DashboardWindows.Window;
import crm_data.patient.service.PatientStats;
import crm_data.scheduling.service.AppointmentStats;
import crm_data.scheduling.service.AppointmentStats.ReturnStats;
import crm_data.scheduling.service.AppointmentStats.StartAndStatus;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Dashboard da clínica: tudo calculado aqui, numa transação somente leitura. As janelas são do fuso da clínica e a semana
 * vai de segunda a domingo. O faturamento só é consultado para o dentista: a recepção nunca dispara a consulta e o campo
 * nem aparece na resposta. Só agregados; nenhum dado pessoal.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DENTIST_ADMIN','RECEPTIONIST')")
public class DashboardService {

	private static final LocalDate EARLIEST_WEEK = LocalDate.of(2000, 1, 1);
	private static final int MAX_DAYS_AHEAD = 366;

	private final PatientStats patients;
	private final AppointmentStats appointments;
	private final BudgetLookup budgets;
	private final ClinicZones zones;
	private final Clock clock;

	/**
	 * @param weekOf qualquer dia da semana desejada; {@code null} = a semana de hoje
	 * @throws BusinessException {@code business-rule} (422, {@code WEEK_OUT_OF_RANGE}) se fora de
	 *         {@code [2000-01-01, hoje + 366 dias]}
	 */
	@Transactional(readOnly = true)
	public DashboardResponse get(LocalDate weekOf) {
		DashboardWindows windows = new DashboardWindows(clock, zones.zone());
		LocalDate today = windows.localToday();
		LocalDate reference = weekOf == null ? today : weekOf;
		if (reference.isBefore(EARLIEST_WEEK) || reference.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A semana está fora do intervalo permitido.",
					"WEEK_OUT_OF_RANGE");
		}

		long totalPatients = patients.activeCount();

		Window todayWindow = windows.today();
		DashboardResponse.Today appointmentsToday = today(appointments.countByStatus(todayWindow.ini(), todayWindow.fim()));

		Window month = windows.currentMonth();
		ReturnStats stats = appointments.returnStats(month.ini(), month.fim(), windows.previousTwelveMonths().ini());
		DashboardResponse.ReturnRate returnRate = new DashboardResponse.ReturnRate(windows.month().toString(), stats.attended(),
				stats.returning(), percent(stats));

		DashboardResponse.MonthlyRevenue revenue = null;
		if (TenantContext.role() == Role.DENTIST_ADMIN) {
			BigDecimal total = budgets.approvedTotal(month.ini(), month.fim());
			revenue = new DashboardResponse.MonthlyRevenue(windows.month().toString(),
					(total == null ? BigDecimal.ZERO : total).setScale(2, RoundingMode.HALF_UP).toPlainString(),
					budgets.approvedCount(month.ini(), month.fim()));
		}

		return new DashboardResponse(clock.instant(), windows.zone().getId(), totalPatients, appointmentsToday, returnRate,
				revenue, weekly(windows, reference));
	}

	private static DashboardResponse.Today today(Map<String, Long> counts) {
		Map<String, Long> byStatus = zeroed();
		counts.forEach((status, count) -> byStatus.merge(status, count, Long::sum));
		return new DashboardResponse.Today(counted(byStatus), byStatus);
	}

	private DashboardResponse.Weekly weekly(DashboardWindows windows, LocalDate reference) {
		Window week = windows.week(reference);
		LocalDate monday = DashboardWindows.weekStart(reference);
		List<StartAndStatus> rows = appointments.startsAndStatus(week.ini(), week.fim());
		Map<LocalDate, Map<String, Long>> perDay = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) {
			perDay.put(monday.plusDays(i), zeroed());
		}
		for (StartAndStatus row : rows) {
			LocalDate day = row.startsAt().atZone(windows.zone()).toLocalDate();
			Map<String, Long> byStatus = perDay.get(day);
			if (byStatus != null) {
				byStatus.merge(row.status(), 1L, Long::sum);
			}
		}
		List<DashboardResponse.Day> days = perDay.entrySet().stream()
				.map(e -> new DashboardResponse.Day(e.getKey(), counted(e.getValue()), e.getValue())).toList();
		return new DashboardResponse.Weekly(monday, monday.plusDays(6), days);
	}

	private static Map<String, Long> zeroed() {
		Map<String, Long> byStatus = new LinkedHashMap<>();
		AppointmentStats.STATUSES.forEach(status -> byStatus.put(status, 0L));
		return byStatus;
	}

	/** Soma sem cancelados e faltas: o horário foi liberado. */
	private static long counted(Map<String, Long> byStatus) {
		return byStatus.entrySet().stream().filter(e -> !AppointmentStats.NOT_COUNTED.contains(e.getKey()))
				.mapToLong(Map.Entry::getValue).sum();
	}

	/** {@code retornos * 100 / atendidos}, 1 casa, {@code HALF_UP}; {@code null} sem atendidos. */
	static String percent(ReturnStats stats) {
		if (stats.attended() == 0) {
			return null;
		}
		return BigDecimal.valueOf(stats.returning()).multiply(BigDecimal.valueOf(100))
				.divide(BigDecimal.valueOf(stats.attended()), 1, RoundingMode.HALF_UP).toPlainString();
	}
}
