<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class AppointmentType extends Model
{
    use HasFactory, \App\Traits\Tenantable, SoftDeletes;

    protected $fillable = ['name', 'duration_minutes', 'color'];

    public function appointments()
    {
        return $this->hasMany(Appointment::class);
    }
}
