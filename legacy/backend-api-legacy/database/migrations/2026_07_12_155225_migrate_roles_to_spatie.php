<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;
use Spatie\Permission\Models\Role;
use Spatie\Permission\Models\Permission;
use App\Models\User;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        // 1. Criar as roles básicas se não existirem
        $manager = Role::firstOrCreate(['name' => 'manager']);
        $doctor = Role::firstOrCreate(['name' => 'doctor']);
        $receptionist = Role::firstOrCreate(['name' => 'receptionist']);

        // 2. Criar permissões básicas
        $permissions = [
            'view-calendar',
            'manage-appointments',
            'view-patients',
            'manage-patients',
            'manage-team',
            'manage-settings'
        ];

        foreach ($permissions as $perm) {
            Permission::firstOrCreate(['name' => $perm]);
        }

        // 3. Atribuir permissões ao manager (todas)
        $manager->syncPermissions($permissions);
        
        // Atribuir algumas pro dentista
        $doctor->syncPermissions(['view-calendar', 'manage-appointments', 'view-patients', 'manage-patients']);

        // Atribuir algumas para recepcionista
        $receptionist->syncPermissions(['view-calendar', 'manage-appointments', 'view-patients', 'manage-patients']);

        // 4. Migrar usuários
        $users = User::all();
        foreach ($users as $user) {
            if ($user->role === 'manager') {
                $user->assignRole($manager);
            } elseif ($user->role === 'doctor') {
                $user->assignRole($doctor);
            } elseif ($user->role === 'receptionist') {
                $user->assignRole($receptionist);
            }
        }

        // 5. Remover a coluna 'role'
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn('role');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('spatie', function (Blueprint $table) {
            //
        });
    }
};
