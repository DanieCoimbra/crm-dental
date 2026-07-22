<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Crypt;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        // 1. Modificar os tipos de coluna para LONGTEXT (ou TEXT longo)
        // O Laravel 11/10 já suporta .change() sem doctrine em alguns drivers, 
        // mas em sqlite text e longtext são iguais. Vamos garantir a alteração de tamanho para quem usa MySQL.
        Schema::table('clinical_evolutions', function (Blueprint $table) {
            $table->longText('content')->nullable()->change();
        });

        Schema::table('prescriptions', function (Blueprint $table) {
            $table->longText('content')->nullable()->change();
        });

        Schema::table('patients', function (Blueprint $table) {
            $table->longText('medical_history')->nullable()->change();
            $table->longText('notes')->nullable()->change();
        });

        // 2. Criptografar registros existentes
        
        // clinical_evolutions
        DB::table('clinical_evolutions')->whereNotNull('content')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                // Tenta ignorar se já parecer um payload do Laravel (começa com eyJ)
                if (!str_starts_with($record->content, 'eyJ')) {
                    DB::table('clinical_evolutions')->where('id', $record->id)->update([
                        'content' => Crypt::encryptString($record->content)
                    ]);
                }
            }
        });

        // prescriptions
        DB::table('prescriptions')->whereNotNull('content')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (!str_starts_with($record->content, 'eyJ')) {
                    DB::table('prescriptions')->where('id', $record->id)->update([
                        'content' => Crypt::encryptString($record->content)
                    ]);
                }
            }
        });

        // patients (medical_history e notes)
        DB::table('patients')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                $updates = [];
                if ($record->medical_history && !str_starts_with($record->medical_history, 'eyJ')) {
                    $updates['medical_history'] = Crypt::encryptString($record->medical_history);
                }
                if ($record->notes && !str_starts_with($record->notes, 'eyJ')) {
                    $updates['notes'] = Crypt::encryptString($record->notes);
                }
                
                if (!empty($updates)) {
                    DB::table('patients')->where('id', $record->id)->update($updates);
                }
            }
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        // clinical_evolutions
        DB::table('clinical_evolutions')->whereNotNull('content')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (str_starts_with($record->content, 'eyJ')) {
                    try {
                        DB::table('clinical_evolutions')->where('id', $record->id)->update([
                            'content' => Crypt::decryptString($record->content)
                        ]);
                    } catch (\Exception $e) {}
                }
            }
        });

        // prescriptions
        DB::table('prescriptions')->whereNotNull('content')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (str_starts_with($record->content, 'eyJ')) {
                    try {
                        DB::table('prescriptions')->where('id', $record->id)->update([
                            'content' => Crypt::decryptString($record->content)
                        ]);
                    } catch (\Exception $e) {}
                }
            }
        });

        // patients
        DB::table('patients')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                $updates = [];
                if ($record->medical_history && str_starts_with($record->medical_history, 'eyJ')) {
                    try { $updates['medical_history'] = Crypt::decryptString($record->medical_history); } catch (\Exception $e) {}
                }
                if ($record->notes && str_starts_with($record->notes, 'eyJ')) {
                    try { $updates['notes'] = Crypt::decryptString($record->notes); } catch (\Exception $e) {}
                }
                if (!empty($updates)) {
                    DB::table('patients')->where('id', $record->id)->update($updates);
                }
            }
        });
    }
};
