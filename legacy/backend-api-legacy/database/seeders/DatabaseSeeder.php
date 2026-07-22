<?php

namespace Database\Seeders;

use App\Models\User;
use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class DatabaseSeeder extends Seeder
{
    /**
     * Seed the application's database.
     */
    public function run(): void
    {
        // Criação de Salas
        \App\Models\Room::create(['name' => 'Sala 01 - Clínica Geral']);
        \App\Models\Room::create(['name' => 'Sala 02 - Cirurgia']);
        \App\Models\Room::create(['name' => 'Sala 03 - Odontopediatria']);

        // Criação de Pacientes
        \App\Models\Patient::create([
            'name' => 'João Silva',
            'cpf' => '111.111.111-11',
            'email' => 'joao@example.com',
            'phone' => '11999999999',
            'address' => 'Rua das Flores, 123'
        ]);
        
        \App\Models\Patient::create([
            'name' => 'Maria Oliveira',
            'cpf' => '222.222.222-22',
            'email' => 'maria@example.com',
            'phone' => '11988888888',
            'address' => 'Avenida Brasil, 456'
        ]);

        // Definir clinic_id base que foi criado pela migration
        $clinicId = 1;
        setPermissionsTeamId($clinicId);

        $admin = User::factory()->create([
            'name' => 'Recepcionista (Admin)',
            'email' => 'admin@admin.com',
            'password' => bcrypt('password'),
            'clinic_id' => $clinicId,
        ]);
        $admin->assignRole('receptionist');

        $medico = User::factory()->create([
            'name' => 'Dr. Carlos (Médico)',
            'email' => 'medico@admin.com',
            'password' => bcrypt('password'),
            'clinic_id' => $clinicId,
        ]);
        $medico->assignRole('doctor');

        $gerente = User::factory()->create([
            'name' => 'Sr. Silva (Gerente)',
            'email' => 'gerente@admin.com',
            'password' => bcrypt('password'),
            'clinic_id' => $clinicId,
        ]);
        $gerente->assignRole('manager');
    }
}
