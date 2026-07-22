<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Clinic extends Model
{
    protected $guarded = ['id'];

    protected $casts = [
        'trial_ends_at' => 'datetime',
    ];
}
