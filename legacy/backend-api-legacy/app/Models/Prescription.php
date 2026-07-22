<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

class Prescription extends Model
{
    use HasFactory, SoftDeletes, \App\Traits\Tenantable;

    protected $casts = [
        'content' => 'encrypted'
    ];

    protected $fillable = [
        'patient_id',
        'user_id',
        'type',
        'title',
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
