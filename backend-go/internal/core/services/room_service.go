package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"errors"
)

type RoomService struct {
	roomRepo *repositories.RoomRepository
}

func NewRoomService() *RoomService {
	return &RoomService{
		roomRepo: repositories.NewRoomRepository(),
	}
}

func (s *RoomService) CreateRoom(clinicID uint, name, description string, isActive bool) (*domain.Room, error) {
	if name == "" {
		return nil, errors.New("o nome da sala é obrigatório")
	}

	room := &domain.Room{
		ClinicID:    clinicID,
		Name:        name,
		Description: description,
		IsActive:    isActive,
	}

	err := s.roomRepo.Create(room)
	if err != nil {
		return nil, err
	}
	return room, nil
}

func (s *RoomService) ListRooms(clinicID uint) ([]domain.Room, error) {
	return s.roomRepo.ListByClinic(clinicID)
}

func (s *RoomService) DeleteRoom(clinicID, id, deletedBy uint) error {
	return s.roomRepo.Delete(id, clinicID, deletedBy)
}

func (s *RoomService) UpdateRoom(clinicID, id uint, name, description string, isActive bool) (*domain.Room, error) {
	room, err := s.roomRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("sala não encontrada")
	}

	if name != "" {
		room.Name = name
	}
	room.Description = description
	room.IsActive = isActive

	if err := s.roomRepo.Update(room); err != nil {
		return nil, err
	}
	return room, nil
}

func (s *RoomService) GetRoom(clinicID, id uint) (*domain.Room, error) {
	room, err := s.roomRepo.FindByID(id, clinicID)
	if err != nil {
		return nil, errors.New("sala não encontrada")
	}
	return room, nil
}
