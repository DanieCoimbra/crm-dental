<?php

namespace Database\Seeders;

use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class SettingSeeder extends Seeder
{
    /**
     * Run the database seeds.
     */
    public function run(): void
    {
        \App\Models\Setting::setValue('allow_weekends', false);
        \App\Models\Setting::setValue('allow_out_of_hours', false);
        \App\Models\Setting::setValue('allow_holidays', false);
        \App\Models\Setting::setValue('custom_holidays', []);
    }
}
