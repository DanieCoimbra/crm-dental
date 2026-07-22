package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"encoding/json"
	"fmt"
	"time"
)

type SettingService struct {
	settingRepo *repositories.SettingRepository
}

func NewSettingService() *SettingService {
	return &SettingService{
		settingRepo: repositories.NewSettingRepository(),
	}
}

func (s *SettingService) GetSetting(clinicID uint, key string, defaultValue string) string {
	return s.settingRepo.GetValue(clinicID, key, defaultValue)
}

func (s *SettingService) SaveSetting(clinicID uint, key string, value string) error {
	return s.settingRepo.SetValue(clinicID, key, value)
}

func (s *SettingService) ListSettings(clinicID uint) ([]domain.Setting, error) {
	return s.settingRepo.ListByClinic(clinicID)
}

type HolidayInfo struct {
	Date string `json:"date"`
	Name string `json:"name"`
	Type string `json:"type"` // "fixed", "movable", "custom"
}

func (s *SettingService) GetHolidaysForYear(clinicID uint, year int) []HolidayInfo {
	fixed := []HolidayInfo{
		{Date: fmt.Sprintf("%d-01-01", year), Name: "Confraternização Universal", Type: "fixed"},
		{Date: fmt.Sprintf("%d-04-21", year), Name: "Tiradentes", Type: "fixed"},
		{Date: fmt.Sprintf("%d-05-01", year), Name: "Dia do Trabalhador", Type: "fixed"},
		{Date: fmt.Sprintf("%d-09-07", year), Name: "Independência do Brasil", Type: "fixed"},
		{Date: fmt.Sprintf("%d-10-12", year), Name: "Nossa Sra. Aparecida", Type: "fixed"},
		{Date: fmt.Sprintf("%d-11-02", year), Name: "Finados", Type: "fixed"},
		{Date: fmt.Sprintf("%d-11-15", year), Name: "Proclamação da República", Type: "fixed"},
		{Date: fmt.Sprintf("%d-12-25", year), Name: "Natal", Type: "fixed"},
	}

	easter := getEaster(year)
	easterDate := easter.Format("2006-01-02")
	goodFriday := easter.AddDate(0, 0, -2).Format("2006-01-02")
	carnival := easter.AddDate(0, 0, -47).Format("2006-01-02")
	corpusChristi := easter.AddDate(0, 0, 60).Format("2006-01-02")

	movable := []HolidayInfo{
		{Date: easterDate, Name: "Páscoa", Type: "movable"},
		{Date: goodFriday, Name: "Paixão de Cristo", Type: "movable"},
		{Date: carnival, Name: "Carnaval", Type: "movable"},
		{Date: corpusChristi, Name: "Corpus Christi", Type: "movable"},
	}

	customHolidaysStr := s.GetSetting(clinicID, "custom_holidays", "[]")
	var customDates []string
	_ = json.Unmarshal([]byte(customHolidaysStr), &customDates)

	var customHolidays []HolidayInfo
	for _, cd := range customDates {
		customHolidays = append(customHolidays, HolidayInfo{Date: cd, Name: "Feriado/Bloqueio Personalizado", Type: "custom"})
	}

	allHolidays := append(fixed, movable...)
	allHolidays = append(allHolidays, customHolidays...)

	uniqueHolidays := make(map[string]bool)
	var result []HolidayInfo
	for _, h := range allHolidays {
		if !uniqueHolidays[h.Date] {
			uniqueHolidays[h.Date] = true
			result = append(result, h)
		}
	}

	return result
}

func getEaster(year int) time.Time {
	a := year % 19
	b := year / 100
	c := year % 100
	d := b / 4
	e := b % 4
	f := (b + 8) / 25
	g := (b - f + 1) / 3
	h := (19*a + b - d - g + 15) % 30
	i := c / 4
	k := c % 4
	l := (32 + 2*e + 2*i - h - k) % 7
	m := (a + 11*h + 22*l) / 451
	month := (h + l - 7*m + 114) / 31
	day := ((h + l - 7*m + 114) % 31) + 1

	return time.Date(year, time.Month(month), day, 0, 0, 0, 0, time.UTC)
}
