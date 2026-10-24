package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.scheduling.domain.BusinessHour;

class DayScheduleTest {

	private static BusinessHour open(LocalTime opens, LocalTime closes, LocalTime lunchStarts, LocalTime lunchEnds) {
		BusinessHour hour = new BusinessHour(UUID.randomUUID(), DayOfWeek.MONDAY, UUID.randomUUID());
		hour.setOpen(true);
		hour.setOpensAt(opens);
		hour.setClosesAt(closes);
		hour.setLunchStartsAt(lunchStarts);
		hour.setLunchEndsAt(lunchEnds);
		return hour;
	}

	@Test
	void closedDayDropsEveryTime() {
		BusinessHour hour = new BusinessHour(UUID.randomUUID(), DayOfWeek.SUNDAY, UUID.randomUUID());

		assertThat(DaySchedule.of(hour)).isEqualTo(DaySchedule.CLOSED);
	}

	@Test
	void openDayCopiesTheTimes() {
		DaySchedule schedule = DaySchedule.of(open(LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)));

		assertThat(schedule).isEqualTo(new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)));
	}

	@Test
	void aClosingTimeBeyond2359WrittenOutsideTheApiIsClampedToTheLastMinute() {
		DaySchedule schedule = DaySchedule.of(open(LocalTime.of(8, 0), LocalTime.MAX, null, null));

		assertThat(schedule.closesAt()).isEqualTo(LocalTime.of(23, 59));
		assertThat(schedule.lunchStartsAt()).isNull();
	}
}
