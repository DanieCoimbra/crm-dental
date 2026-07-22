<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Factories\HasFactory;

class Setting extends Model
{
    use HasFactory, \App\Traits\Tenantable;

    protected $fillable = ['key', 'value'];

    public static function getValue($key, $default = null)
    {
        $setting = self::where('key', $key)->first();
        if (!$setting) return $default;
        
        $decoded = json_decode($setting->value, true);
        if (json_last_error() === JSON_ERROR_NONE) {
            return $decoded;
        }
        return $setting->value;
    }

    public static function setValue($key, $value)
    {
        $valueToSave = is_array($value) || is_bool($value) ? json_encode($value) : $value;
        return self::updateOrCreate(['key' => $key], ['value' => $valueToSave]);
    }
}
