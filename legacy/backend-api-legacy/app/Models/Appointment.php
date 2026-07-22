<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class Appointment extends Model
{
    use HasFactory, SoftDeletes, \App\Traits\Tenantable;

    protected $casts = [
        'notes' => 'encrypted'
    ];
    protected $fillable = ['doctor_id', 'patient_id', 'room_id', 'appointment_type_id', 'start_time', 'end_time', 'notes', 'status', 'actual_start_time', 'actual_end_time'];

    public function doctor() { return $this->belongsTo(User::class, 'doctor_id'); }
    public function patient() { return $this->belongsTo(Patient::class); }
    public function room() { return $this->belongsTo(Room::class); }
    public function appointmentType() { return $this->belongsTo(AppointmentType::class); }

    public function deletedBy()
    {
        return $this->belongsTo(User::class, 'deleted_by');
    }

    protected static function booted()
    {
        static::deleting(function ($model) {
            if (auth()->check()) {
                $model->deleted_by = auth()->id();
                $model->saveQuietly();
            }
        });

        static::restoring(function ($model) {
            $model->deleted_by = null;
            $model->saveQuietly();
        });
    }
}
