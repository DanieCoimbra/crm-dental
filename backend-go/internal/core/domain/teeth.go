package domain

import (
	"time"
)

type ToothFace string

const (
	FaceMesial     ToothFace = "MESIAL"
	FaceDistal     ToothFace = "DISTAL"
	FaceOclusal    ToothFace = "OCLUSAL"
	FaceIncisal    ToothFace = "INCISAL"
	FaceVestibular ToothFace = "VESTIBULAR"
	FacePalatina   ToothFace = "PALATINA"
	FaceLingual    ToothFace = "LINGUAL"
	FaceGeral      ToothFace = "GERAL"
)

type ToothCondition string

const (
	ConditionHigido          ToothCondition = "HIGIDO"
	ConditionCariado         ToothCondition = "CARIADO"
	ConditionRestaurado      ToothCondition = "RESTAURADO"
	ConditionExtraido        ToothCondition = "EXTRAIDO"
	ConditionImplante        ToothCondition = "IMPLANTE"
	ConditionTratamentoCanal ToothCondition = "TRATAMENTO_CANAL"
	ConditionCoroaProtese    ToothCondition = "COROA_PROTESE"
	ConditionEmTratamento    ToothCondition = "EM_TRATAMENTO"
)

func ValidateFDITooth(toothNumber int) bool {
	return (toothNumber >= 11 && toothNumber <= 48) || (toothNumber >= 51 && toothNumber <= 85)
}

func ValidateToothFace(face ToothFace) bool {
	switch face {
	case FaceMesial, FaceDistal, FaceOclusal, FaceIncisal, FaceVestibular, FacePalatina, FaceLingual, FaceGeral:
		return true
	default:
		return false
	}
}

func ValidateToothCondition(cond ToothCondition) bool {
	switch cond {
	case ConditionHigido, ConditionCariado, ConditionRestaurado, ConditionExtraido, ConditionImplante, ConditionTratamentoCanal, ConditionCoroaProtese, ConditionEmTratamento:
		return true
	default:
		return false
	}
}

type TeethStatus struct {
	ID          uint           `gorm:"primaryKey" json:"id"`
	ClinicID    uint           `gorm:"not null;index:idx_teeth_status_patient" json:"clinic_id"`
	Clinic      *Clinic        `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	PatientID   uint           `gorm:"not null;index:idx_teeth_status_patient" json:"patient_id"`
	Patient     *Patient       `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	ToothNumber int            `gorm:"not null" json:"tooth_number"`
	Face        ToothFace      `gorm:"type:varchar(50);not null;default:'GERAL'" json:"face"`
	Condition   ToothCondition `gorm:"type:varchar(50);not null;default:'HIGIDO'" json:"condition"`
	UpdatedBy   uint           `gorm:"not null" json:"updated_by"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

func (TeethStatus) TableName() string {
	return "teeth_status"
}

type TeethHistory struct {
	ID                uint            `gorm:"primaryKey" json:"id"`
	ClinicID          uint            `gorm:"not null;index:idx_teeth_history_lookup" json:"clinic_id"`
	Clinic            *Clinic         `gorm:"foreignKey:ClinicID" json:"clinic,omitempty"`
	PatientID         uint            `gorm:"not null;index:idx_teeth_history_lookup" json:"patient_id"`
	Patient           *Patient        `gorm:"foreignKey:PatientID" json:"patient,omitempty"`
	TeethStatusID     *uint           `json:"teeth_status_id,omitempty"`
	ToothNumber       int             `gorm:"not null;index:idx_teeth_history_lookup" json:"tooth_number"`
	Face              ToothFace       `gorm:"type:varchar(50);not null" json:"face"`
	PreviousCondition *ToothCondition `gorm:"type:varchar(50)" json:"previous_condition"`
	NewCondition      ToothCondition  `gorm:"type:varchar(50);not null" json:"new_condition"`
	Notes             string          `gorm:"type:text" json:"notes,omitempty"`
	CreatedBy         uint            `gorm:"not null" json:"created_by"`
	CreatedAt         time.Time       `json:"created_at"`
}

func (TeethHistory) TableName() string {
	return "teeth_history"
}
