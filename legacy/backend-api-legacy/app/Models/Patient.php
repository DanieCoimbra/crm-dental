<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class Patient extends Model
{
    use HasFactory, SoftDeletes, \App\Traits\Tenantable;

    protected $casts = [
        'medical_history' => 'encrypted',
        'notes' => 'encrypted'
    ];

    protected $fillable = [
        'name',
        'cpf',
        'email',
        'phone',
        'address',
        'health_insurance',
        'birth_date',
        'medical_history',
        'notes'
    ];

    public function deletedBy()
    {
        return $this->belongsTo(User::class, 'deleted_by');
    }

    public function clinicalEvolutions()
    {
        return $this->hasMany(ClinicalEvolution::class);
    }

    public function patientFiles()
    {
        return $this->hasMany(PatientFile::class);
    }

    public function prescriptions()
    {
        return $this->hasMany(Prescription::class);
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
