import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/marketing/data/marketing_model.dart';

final marketingRepositoryProvider = Provider<MarketingRepository>((ref) {
  return MarketingRepository(ref.watch(dioProvider));
});

class MarketingRepository {
  final Dio _dio;
  MarketingRepository(this._dio);

  Future<List<PromoCode>> getPromoCodes() async {
    final res = await _dio.get('/marketing/promo-codes');
    return (res.data as List).map((e) => PromoCode.fromJson(e)).toList();
  }

  Future<PromoCode> createPromoCode(Map<String, dynamic> data) async {
    final res = await _dio.post('/marketing/promo-codes', data: data);
    return PromoCode.fromJson(res.data);
  }

  Future<void> deletePromoCode(String id) async {
    await _dio.delete('/marketing/promo-codes/$id');
  }

  Future<List<ReferralPartner>> getPartners() async {
    final res = await _dio.get('/marketing/partners');
    return (res.data as List).map((e) => ReferralPartner.fromJson(e)).toList();
  }

  Future<ReferralPartner> createPartner(Map<String, dynamic> data) async {
    final res = await _dio.post('/marketing/partners', data: data);
    return ReferralPartner.fromJson(res.data);
  }

  Future<void> deletePartner(String id) async {
    await _dio.delete('/marketing/partners/$id');
  }
}
