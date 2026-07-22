<?php

namespace App\Services;

use Carbon\Carbon;
use App\Models\Setting;

class HolidayService
{
    /**
     * Verifica se uma data é feriado (nacional ou customizado)
     */
    public static function isHoliday(Carbon $date): bool
    {
        $dateString = $date->format('Y-m-d');
        $holidays = self::getHolidaysForYear($date->year);

        return in_array($dateString, $holidays);
    }

    /**
     * Retorna um array com todas as datas de feriados ('Y-m-d') do ano solicitado.
     */
    public static function getHolidaysForYear(int $year): array
    {
        // 1. Feriados Fixos
        $fixed = [
            "$year-01-01", // Confraternização Universal
            "$year-04-21", // Tiradentes
            "$year-05-01", // Dia do Trabalhador
            "$year-09-07", // Independência do Brasil
            "$year-10-12", // Nossa Senhora Aparecida
            "$year-11-02", // Finados
            "$year-11-15", // Proclamação da República
            "$year-12-25", // Natal
        ];

        // 2. Feriados Móveis (baseados na Páscoa)
        $easterDays = easter_days($year);
        $easter = Carbon::createFromDate($year, 3, 21)->addDays($easterDays);
        
        $easterDate = $easter->format('Y-m-d');
        $goodFriday = $easter->copy()->subDays(2)->format('Y-m-d'); // Sexta-feira Santa
        $carnival = $easter->copy()->subDays(47)->format('Y-m-d'); // Carnaval
        $corpusChristi = $easter->copy()->addDays(60)->format('Y-m-d'); // Corpus Christi

        $movable = [$easterDate, $goodFriday, $carnival, $corpusChristi];

        // 3. Feriados Customizados (do banco de dados)
        $customHolidays = Setting::getValue('custom_holidays', []);

        // Array_merge para todos
        $allHolidays = array_merge($fixed, $movable, $customHolidays);

        return array_unique($allHolidays);
    }
}
