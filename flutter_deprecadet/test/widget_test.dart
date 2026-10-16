import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('App base widget test structure', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: Text('Dental CRM Test'),
        ),
      ),
    );

    expect(find.text('Dental CRM Test'), findsOneWidget);
  });
}
