import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/marketing/data/marketing_model.dart';
import 'package:frontend_flutter/features/marketing/data/marketing_repository.dart';

final promoCodesProvider = FutureProvider<List<PromoCode>>((ref) async {
  return ref.watch(marketingRepositoryProvider).getPromoCodes();
});

final partnersProvider = FutureProvider<List<ReferralPartner>>((ref) async {
  return ref.watch(marketingRepositoryProvider).getPartners();
});
