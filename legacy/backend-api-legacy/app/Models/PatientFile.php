<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class PatientFile extends Model
{
    use HasFactory, SoftDeletes, \App\Traits\Tenantable;

    protected $fillable = [
        'patient_id',
        'file_name',
        'file_path',
        'file_type',
        'category',
    ];

    public function patient()
    {
        return $this->belongsTo(Patient::class);
    }
}
