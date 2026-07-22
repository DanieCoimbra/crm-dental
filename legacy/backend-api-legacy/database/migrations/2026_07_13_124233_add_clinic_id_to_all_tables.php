<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;
use Illuminate\Support\Facades\DB;

return new class extends Migration
{
    public function up(): void
    {
        // Cria a Clínica 1 para os dados existentes
        $clinicId = DB::table('clinics')->insertGetId([
            'name' => 'Clínica Principal',
            'cnpj' => '00.000.000/0000-00',
            'status' => 'active',
            'created_at' => now(),
            'updated_at' => now(),
        ]);

        $tables = [
            'users', 'patients', 'appointments', 'appointment_types', 
            'rooms', 'clinical_evolutions', 'patient_files', 
            'prescriptions', 'settings', 'waitlists', 'whats_app_logs'
        ];

        foreach ($tables as $tableName) {
            if (Schema::hasTable($tableName)) {
                Schema::table($tableName, function (Blueprint $table) {
                    $table->unsignedBigInteger('clinic_id')->nullable();
                });
                
                DB::table($tableName)->update(['clinic_id' => $clinicId]);

                Schema::table($tableName, function (Blueprint $table) {
                    $table->foreign('clinic_id')->references('id')->on('clinics')->onDelete('cascade');
                });
            }
        }
    }

    public function down(): void
    {
        $tables = [
            'users', 'patients', 'appointments', 'appointment_types', 
            'rooms', 'clinical_evolutions', 'patient_files', 
            'prescriptions', 'settings', 'waitlists', 'whats_app_logs'
        ];

        foreach ($tables as $tableName) {
            if (Schema::hasTable($tableName)) {
                Schema::table($tableName, function (Blueprint $table) {
                    $table->dropForeign(['clinic_id']);
                    $table->dropColumn('clinic_id');
                });
            }
        }
    }
};
