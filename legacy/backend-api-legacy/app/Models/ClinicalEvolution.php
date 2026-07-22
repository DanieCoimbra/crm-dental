<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class ClinicalEvolution extends Model
{
    use HasFactory, SoftDeletes, \App\Traits\Tenantable;

    protected $casts = [
        'content' => 'encrypted'
    ];

    protected $fillable = [
        'patient_id',
        'user_id',
        'content',
    ];

    public function patient()
    {
        return $this->belongsTo(Patient::class);
    }

    public function user()
    {
        return $this->belongsTo(User::class);
    }
}
