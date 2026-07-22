package services

import (
	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"encoding/json"
	"errors"
)

type RoleService struct {
	roleRepo *repositories.RoleRepository
}

func NewRoleService() *RoleService {
	return &RoleService{
		roleRepo: repositories.NewRoleRepository(),
	}
}

func (s *RoleService) GetAvailablePermissions() []string {
	return []string{
		"patients_view", "patients_edit", "patients_delete",
		"appointments_view", "appointments_edit", "appointments_delete",
		"waitlist_manage",
		"rooms_manage",
		"settings_manage",
		"roles_manage",
		"financial_view", "financial_edit",
	}
}

func (s *RoleService) ListRoles(clinicID uint) ([]domain.Role, error) {
	return s.roleRepo.List(clinicID)
}

func (s *RoleService) CreateRole(clinicID uint, name string, permissions []string) (*domain.Role, error) {
	if name == "" {
		return nil, errors.New("o nome do cargo é obrigatório")
	}

	permissionsJSON, err := json.Marshal(permissions)
	if err != nil {
		return nil, errors.New("erro ao formatar permissões")
	}

	role := &domain.Role{
		Name:        name,
		Permissions: string(permissionsJSON),
		ClinicID:    &clinicID,
	}

	if err := s.roleRepo.Create(role); err != nil {
		return nil, err
	}

	return role, nil
}

func (s *RoleService) UpdateRole(clinicID, id uint, name string, permissions []string) (*domain.Role, error) {
	role, err := s.roleRepo.FindByID(id)
	if err != nil {
		return nil, errors.New("cargo não encontrado")
	}

	if role.ClinicID == nil {
		return nil, errors.New("cargos globais do sistema não podem ser alterados")
	}

	if *role.ClinicID != clinicID {
		return nil, errors.New("cargo não pertence à sua clínica")
	}

	if name != "" {
		role.Name = name
	}

	if permissions != nil {
		permissionsJSON, err := json.Marshal(permissions)
		if err != nil {
			return nil, errors.New("erro ao formatar permissões")
		}
		role.Permissions = string(permissionsJSON)
	}

	if err := s.roleRepo.Update(role); err != nil {
		return nil, err
	}

	return role, nil
}

func (s *RoleService) DeleteRole(clinicID, id uint) error {
	role, err := s.roleRepo.FindByID(id)
	if err != nil {
		return errors.New("cargo não encontrado")
	}

	if role.ClinicID == nil {
		return errors.New("cargos globais do sistema não podem ser excluídos")
	}

	if *role.ClinicID != clinicID {
		return errors.New("cargo não pertence à sua clínica")
	}

	return s.roleRepo.Delete(id)
}

func (s *RoleService) GetRole(id uint) (*domain.Role, error) {
	return s.roleRepo.FindByID(id)
}
