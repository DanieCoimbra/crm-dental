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
        Schema::table('appointments', function (Blueprint $table) {
            $table->longText('notes')->nullable()->change();
        });

        Schema::table('waitlists', function (Blueprint $table) {
            $table->longText('notes')->nullable()->change();
        });

        // Criptografar registros existentes
        DB::table('appointments')->whereNotNull('notes')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (!str_starts_with($record->notes, 'eyJ')) {
                    DB::table('appointments')->where('id', $record->id)->update([
                        'notes' => Crypt::encryptString($record->notes)
                    ]);
                }
            }
        });

        DB::table('waitlists')->whereNotNull('notes')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (!str_starts_with($record->notes, 'eyJ')) {
                    DB::table('waitlists')->where('id', $record->id)->update([
                        'notes' => Crypt::encryptString($record->notes)
                    ]);
                }
            }
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        DB::table('appointments')->whereNotNull('notes')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (str_starts_with($record->notes, 'eyJ')) {
                    try {
                        DB::table('appointments')->where('id', $record->id)->update([
                            'notes' => Crypt::decryptString($record->notes)
                        ]);
                    } catch (\Exception $e) {}
                }
            }
        });

        DB::table('waitlists')->whereNotNull('notes')->orderBy('id')->chunk(100, function ($records) {
            foreach ($records as $record) {
                if (str_starts_with($record->notes, 'eyJ')) {
                    try {
                        DB::table('waitlists')->where('id', $record->id)->update([
                            'notes' => Crypt::decryptString($record->notes)
                        ]);
                    } catch (\Exception $e) {}
                }
            }
        });
    }
};
