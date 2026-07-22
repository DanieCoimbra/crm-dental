<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

class Waitlist extends Model
{
    use HasFactory, \App\Traits\Tenantable;

    protected $fillable = [
        'patient_id',
        'doctor_id',
        'appointment_type_id',
        'preferred_days',
        'preferred_time_range',
        'urgency_level',
        'notes',
        'status',
    ];

    protected $casts = [
        'preferred_days' => 'array',
        'notes' => 'encrypted',
    ];

    public function patient()
    {
        return $this->belongsTo(Patient::class);
    }

    public function doctor()
    {
        return $this->belongsTo(User::class, 'doctor_id');
    }

    public function appointmentType()
    {
        return $this->belongsTo(AppointmentType::class);
    }
}
