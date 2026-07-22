<?php

namespace App\Services;

use App\Models\User;
use App\Models\Patient;
use App\Models\Appointment;

class TrashService
{
    public const TRASH_LIMIT = 50;
    public const TRASH_WARNING_THRESHOLD = 40;

    public static function getStatus()
    {
        $usersCount = User::onlyTrashed()->count();
        $patientsCount = Patient::onlyTrashed()->count();
        $appointmentsCount = Appointment::onlyTrashed()->count();

        $total = $usersCount + $patientsCount + $appointmentsCount;

        return [
            'total' => $total,
            'limit' => self::TRASH_LIMIT,
            'is_almost_full' => $total >= self::TRASH_WARNING_THRESHOLD,
            'is_full' => $total >= self::TRASH_LIMIT,
        ];
    }

    public static function isFull()
    {
        return self::getStatus()['is_full'];
    }

    public static function checkLimit()
    {
        if (self::isFull()) {
            abort(403, 'A lixeira está cheia. Esvazie a lixeira para poder excluir novos itens.');
        }
    }
}
