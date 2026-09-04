-- Migration: 20260904000003_seed_data.sql
-- Description: Carga de dados padrão (Cargos, Clínica e Usuário Administrador Demo)

-- 1. Seed de Roles Globais
INSERT INTO public.roles (id, name, permissions) VALUES
(1, 'owner', '["manage_team", "manage_settings", "manage_billing", "view_reports", "manage_appointments"]'),
(2, 'manager', '["manage_team", "manage_settings", "view_reports", "manage_appointments"]'),
(3, 'doctor', '["manage_medical_records", "view_appointments"]'),
(4, 'receptionist', '["manage_appointments", "view_patients", "manage_waitlist"]')
ON CONFLICT (id) DO UPDATE SET
name = EXCLUDED.name,
permissions = EXCLUDED.permissions;

-- 2. Seed de Clínica Padrão Demo
INSERT INTO public.clinics (id, name, cnpj, email, status, plan, trial_ends_at) VALUES
(1, 'Clínica Odontológica Demo', '00.000.000/0001-00', 'admin@clinica.com', 'active', 'premium', NOW() + INTERVAL '365 days')
ON CONFLICT (id) DO NOTHING;

-- 3. Seed de Usuário Administrador Demo (Senha: 123456 / hash bcrypt)
INSERT INTO public.users (id, clinic_id, role_id, name, email, password) VALUES
(1, 1, 1, 'Administrador Demo', 'admin@clinica.com', '$2a$10$wN35R5vG/n8KqJvI8P1r5u0S.K6r7N6xQ1q1y1z1w1x1y1z1w1x1y')
ON CONFLICT (id) DO NOTHING;
