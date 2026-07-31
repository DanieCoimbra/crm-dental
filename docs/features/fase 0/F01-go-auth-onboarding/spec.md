# Especificação Técnica: Auth & Tenant Onboarding

## 1. Visão Geral Técnica
- **Domínio:** `F01-go-auth-onboarding`
- **Componentes:** `AuthHandler`, `AuthUseCase`, `UserRepository`, `ClinicRepository`

## 2. Fluxo Atômico de Registro de Clínica

```go
// Exemplo de pseudocódigo da transação no UseCase
func (u *authUseCase) RegisterClinic(ctx context.Context, req RegisterClinicRequest) (*AuthResponse, error) {
    tx := u.db.Begin()
    
    // 1. Criar Clínica
    clinic := &domain.Clinic{
        Name: req.ClinicName,
        Email: req.ClinicEmail,
        TrialEndsAt: time.Now().AddDate(0, 0, 14),
        Status: "active",
    }
    if err := tx.Create(clinic).Error; err != nil {
        tx.Rollback()
        return nil, err
    }
    
    // 2. Criar Usuário Admin
    user := &domain.User{
        ClinicID: clinic.ID,
        Name: req.AdminName,
        Email: req.AdminEmail,
        PasswordHash: hashPassword(req.AdminPassword),
        Role: "admin",
    }
    if err := tx.Create(user).Error; err != nil {
        tx.Rollback()
        return nil, err
    }
    
    tx.Commit()
    token := generateJWT(user.ID, clinic.ID, user.Role)
    return &AuthResponse{Token: token, User: user, Clinic: clinic}, nil
}
```
