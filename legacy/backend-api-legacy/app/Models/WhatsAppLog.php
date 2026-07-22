<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

class WhatsAppLog extends Model
{
    use HasFactory, \App\Traits\Tenantable;

    protected $fillable = [
        'patient_id',
        'phone_number',
        'message_type',
        'message_body',
        'status',
        'sent_at'
    ];

    protected $casts = [
        'sent_at' => 'datetime',
    ];

    public function patient()
    {
        return $this->belongsTo(Patient::class);
    }
}
