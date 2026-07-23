package handlers

import (
	"dental-crm-api/internal/database"
	"fmt"
	"time"

	"github.com/gofiber/fiber/v2"
)

type DashboardHandler struct{}

func NewDashboardHandler() *DashboardHandler {
	return &DashboardHandler{}
}

func (h *DashboardHandler) GetStats(c *fiber.Ctx) error {
	clinicID := uint(c.Locals("clinic_id").(float64))

	var totalPatients int64
	database.DB.Table("patients").Where("clinic_id = ? AND deleted_at IS NULL", clinicID).Count(&totalPatients)

	var appointmentsToday int64
	todayStart := time.Now().Truncate(24 * time.Hour)
	todayEnd := todayStart.Add(24 * time.Hour)
	database.DB.Table("appointments").
		Where("clinic_id = ? AND start_time >= ? AND start_time < ?", clinicID, todayStart, todayEnd).
		Count(&appointmentsToday)

	var newPatientsThisMonth int64
	monthStart := time.Date(time.Now().Year(), time.Now().Month(), 1, 0, 0, 0, 0, time.Local)
	database.DB.Table("patients").Where("clinic_id = ? AND deleted_at IS NULL AND created_at >= ?", clinicID, monthStart).Count(&newPatientsThisMonth)

	var totalAppointmentsThisMonth int64
	database.DB.Table("appointments").Where("clinic_id = ? AND start_time >= ?", clinicID, monthStart).Count(&totalAppointmentsThisMonth)

	var returnRateStr string
	if totalAppointmentsThisMonth == 0 {
		returnRateStr = "0%"
	} else {
		returning := float64(totalAppointmentsThisMonth - newPatientsThisMonth)
		if returning < 0 {
			returning = 0
		}
		rate := (returning / float64(totalAppointmentsThisMonth)) * 100
		returnRateStr = fmt.Sprintf("%.0f%%", rate)
	}

	now := time.Now()
	offset := int(time.Monday - now.Weekday())
	if offset > 0 {
		offset = -6
	}
	weekStart := now.AddDate(0, 0, offset).Truncate(24 * time.Hour)

	weeklyData := make([]int64, 7)
	revenueData := make([]float64, 7)
	for i := 0; i < 7; i++ {
		dayStart := weekStart.AddDate(0, 0, i)
		dayEnd := dayStart.Add(24 * time.Hour)

		// Appointments count
		var count int64
		database.DB.Table("appointments").
			Where("clinic_id = ? AND start_time >= ? AND start_time < ?", clinicID, dayStart, dayEnd).
			Count(&count)
		weeklyData[i] = count

		// Revenue sum
		var revCents int64
		database.DB.Table("clinic_installments").
			Joins("JOIN clinic_transactions ON clinic_transactions.id = clinic_installments.transaction_id").
			Where("clinic_installments.clinic_id = ? AND clinic_installments.status = 'paid' AND clinic_installments.updated_at >= ? AND clinic_installments.updated_at < ? AND clinic_transactions.type = 'income'", clinicID, dayStart, dayEnd).
			Select("COALESCE(SUM(clinic_installments.amount_cents), 0)").
			Scan(&revCents)
		revenueData[i] = float64(revCents) / 100.0
	}

	// Cancellation Rate
	var cancellations int64
	database.DB.Table("appointments").
		Where("clinic_id = ? AND start_time >= ? AND status IN ('cancelled', 'no_show')", clinicID, monthStart).
		Count(&cancellations)

	cancellationRateStr := "0%"
	if totalAppointmentsThisMonth > 0 {
		rate := (float64(cancellations) / float64(totalAppointmentsThisMonth)) * 100
		cancellationRateStr = fmt.Sprintf("%.0f%%", rate)
	}

	// Monthly Revenue
	var monthlyRevenueCents int64
	database.DB.Table("clinic_installments").
		Joins("JOIN clinic_transactions ON clinic_transactions.id = clinic_installments.transaction_id").
		Where("clinic_installments.clinic_id = ? AND clinic_installments.status = 'paid' AND clinic_installments.updated_at >= ? AND clinic_transactions.type = 'income'", clinicID, monthStart).
		Select("COALESCE(SUM(clinic_installments.amount_cents), 0)").
		Scan(&monthlyRevenueCents)

	monthlyRevenue := float64(monthlyRevenueCents) / 100.0

	// Status Distribution for PieChart
	type StatusCount struct {
		Status string
		Count  int64
	}
	var statuses []StatusCount
	database.DB.Table("appointments").
		Select("status, COUNT(*) as count").
		Where("clinic_id = ? AND start_time >= ?", clinicID, monthStart).
		Group("status").
		Scan(&statuses)

	statusMap := make(map[string]int64)
	for _, s := range statuses {
		statusMap[s.Status] = s.Count
	}

	return c.JSON(fiber.Map{
		"totalPatients":        totalPatients,
		"newPatientsThisMonth": newPatientsThisMonth,
		"appointmentsToday":    appointmentsToday,
		"returnRate":           returnRateStr,
		"cancellationRate":     cancellationRateStr,
		"monthlyRevenue":       monthlyRevenue,
		"statusDistribution":   statusMap,
		"weeklyData":           weeklyData,
		"revenueData":          revenueData,
	})
}
