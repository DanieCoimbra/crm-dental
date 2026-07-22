package services

import (
	"errors"
	"os"
	"time"

	"dental-crm-api/internal/adapters/repositories"
	"dental-crm-api/internal/core/domain"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
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
	// Verificar se CNPJ já existe
	if _, err := s.clinicRepo.FindByCNPJ(cnpj); err == nil {
		return nil, errors.New("CNPJ já cadastrado")
	}

	// Verificar se Email já existe
	if _, err := s.userRepo.FindByEmail(userEmail); err == nil {
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
		Name:        clinicName,
		CNPJ:        cnpj,
		Email:       userEmail,
		Status:      "trial",
		TrialEndsAt: &trialEndsAt,
	}
	if err := s.clinicRepo.Create(clinic); err != nil {
		return nil, err
	}

	// Obter role "owner"
	ownerRole, _ := s.roleRepo.FindByName("owner")

	// Criar o Usuário
	user := &domain.User{
		Name:     userName,
		Email:    userEmail,
		Password: string(hashedPassword),
		ClinicID: clinic.ID,
		RoleID:   &ownerRole.ID,
	}

	if err := s.userRepo.Create(user); err != nil {
		return nil, err
	}

	return user, nil
}

// Login valida o usuário e gera o token JWT
func (s *AuthService) Login(email, password string) (string, *domain.User, error) {
	user, err := s.userRepo.FindByEmail(email)
	if err != nil {
		return "", nil, errors.New("credenciais inválidas")
	}

	// Verificar password
	err = bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(password))
	if err != nil {
		return "", nil, errors.New("credenciais inválidas")
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

	return tokenString, user, nil
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
