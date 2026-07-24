package services

import (
	"errors"
	"os"
	"strings"
	"time"

	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"
	"dental-crm-api/internal/database"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

type AuthService struct {
	userRepo   *repositories.UserRepository
	clinicRepo *repositories.ClinicRepository
	roleRepo   *repositories.RoleRepository
}

func NewAuthService() *AuthService {
	return &AuthService{
		userRepo:   repositories.NewUserRepository(),
		clinicRepo: repositories.NewClinicRepository(),
		roleRepo:   repositories.NewRoleRepository(),
	}
}

func (s *AuthService) GetUserRepository() *repositories.UserRepository {
	return s.userRepo
}

// RegisterClinicOwner registra a clínica e o dono ao mesmo tempo
func (s *AuthService) RegisterClinicOwner(clinicName, cnpj, userEmail, userName, password string) (*domain.User, error) {
	cleanEmail := strings.ToLower(strings.TrimSpace(userEmail))

	// Verificar se CNPJ já existe
	if _, err := s.clinicRepo.FindByCNPJ(cnpj); err == nil {
		return nil, errors.New("CNPJ já cadastrado")
	}

	// Verificar se Email já existe
	if _, err := s.userRepo.FindByEmail(cleanEmail); err == nil {
		return nil, errors.New("E-mail já cadastrado")
	}

	// Hash password
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, err
	}

	// Criar a clínica
	trialEndsAt := time.Now().Add(14 * 24 * time.Hour)
	clinic := &domain.Clinic{
		Name:        strings.TrimSpace(clinicName),
		CNPJ:        strings.TrimSpace(cnpj),
		Email:       cleanEmail,
		Status:      "trial",
		TrialEndsAt: &trialEndsAt,
	}

	var user *domain.User

	err = database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(clinic).Error; err != nil {
			return err
		}

		// Obter role "owner"
		ownerRole, _ := s.roleRepo.FindByName("owner")

		// Criar o Usuário
		user = &domain.User{
			Name:     strings.TrimSpace(userName),
			Email:    cleanEmail,
			Password: string(hashedPassword),
			ClinicID: clinic.ID,
			RoleID:   &ownerRole.ID,
		}

		if err := tx.Create(user).Error; err != nil {
			return err
		}
		return nil
	})

	if err != nil {
		return nil, err
	}

	return user, nil
}

func (s *AuthService) Login(email, password string) (string, *domain.User, *time.Time, error) {
	cleanEmail := strings.ToLower(strings.TrimSpace(email))
	user, err := s.userRepo.FindByEmail(cleanEmail)
	if err != nil {
		return "", nil, nil, errors.New("credenciais inválidas")
	}

	// Verificar se a conta está bloqueada
	if user.LockedUntil != nil && user.LockedUntil.After(time.Now()) {
		return "", nil, user.LockedUntil, errors.New("Muitas tentativas falhas. Conta bloqueada temporariamente.")
	}

	// Verificar password
	err = bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(password))
	if err != nil {
		user.FailedAttempts++
		if user.FailedAttempts >= 5 {
			lockTime := time.Now().Add(15 * time.Minute)
			user.LockedUntil = &lockTime
		}
		s.userRepo.Update(user)
		if user.LockedUntil != nil {
			return "", nil, user.LockedUntil, errors.New("Muitas tentativas falhas. Conta bloqueada temporariamente.")
		}
		return "", nil, nil, errors.New("credenciais inválidas")
	}

	// Se o login for bem-sucedido, reseta as tentativas
	if user.FailedAttempts > 0 || user.LockedUntil != nil {
		user.FailedAttempts = 0
		user.LockedUntil = nil
		s.userRepo.Update(user)
	}

	// Gerar JWT
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":       user.ID,
		"clinic_id": user.ClinicID,
		"role":      user.Role.Name,
		"exp":       time.Now().Add(time.Hour * 24 * 7).Unix(), // 7 dias
	})

	secret := os.Getenv("JWT_SECRET")
	tokenString, err := token.SignedString([]byte(secret))
	if err != nil {
		return "", nil, err
	}

	return tokenString, user, nil, nil
}

func (s *AuthService) UpdateProfile(userID uint, name, email, password string) (*domain.User, error) {
	user, err := s.userRepo.FindByID(userID)
	if err != nil {
		return nil, errors.New("usuário não encontrado")
	}

	if name != "" {
		user.Name = name
	}
	if email != "" && email != user.Email {
		// Verify if email is taken
		if _, err := s.userRepo.FindByEmail(email); err == nil {
			return nil, errors.New("e-mail já está em uso")
		}
		user.Email = email
	}
	if password != "" {
		hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
		if err != nil {
			return nil, err
		}
		user.Password = string(hashedPassword)
	}

	if err := s.userRepo.Update(user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *AuthService) UpdateAvatar(userID uint, avatarURL string) (*domain.User, error) {
	user, err := s.userRepo.FindByID(userID)
	if err != nil {
		return nil, errors.New("usuário não encontrado")
	}

	user.Avatar = avatarURL
	if err := s.userRepo.Update(user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *AuthService) UpdateRoom(userID uint, roomID *uint) (*domain.User, error) {
	user, err := s.userRepo.FindByID(userID)
	if err != nil {
		return nil, errors.New("usuário não encontrado")
	}

	user.CurrentRoomID = roomID
	if err := s.userRepo.Update(user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *AuthService) UpdatePreferences(userID uint, themePreference string) (*domain.User, error) {
	user, err := s.userRepo.FindByID(userID)
	if err != nil {
		return nil, errors.New("usuário não encontrado")
	}

	if themePreference != "" {
		user.ThemePreference = themePreference
	}

	if err := s.userRepo.Update(user); err != nil {
		return nil, err
	}
	return user, nil
}
