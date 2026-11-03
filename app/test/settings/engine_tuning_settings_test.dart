import 'dart:async';

import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/ui/settings/engine_tuning_settings.dart';
import 'package:dsh_app/ui/settings/settings_widgets.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Bridge extends RuntimeBridge {
  _Bridge(this.items);

  List<TuningItem> items;
  final allowDangerousCalls = <bool>[];
  final accessibilityServices = <String>[];
  Completer<TuningResult?>? accessibilityPending;
  TuningResult? accessibilityResult;

  @override
  Future<List<TuningItem>> tuningStatus() async => items;

  @override
  Future<List<TuningResult>> applyTuning({bool allowDangerous = false}) async {
    allowDangerousCalls.add(allowDangerous);
    return const [];
  }

  @override
  Future<TuningResult?> applyAccessibilityTuning(String service) async {
    accessibilityServices.add(service);
    if (accessibilityPending case final pending?) await pending.future;
    return accessibilityResult ??
        const TuningResult(id: 'accessibilityAuto', alreadyApplied: false, applied: true);
  }
}

const _accessibility = TuningItem(
  id: 'accessibilityAuto',
  title: '自动恢复无障碍服务',
  detail: '允许应用在服务被系统关闭后自行恢复。',
  persistent: true,
  dangerous: true,
  applied: false,
);

void main() {
  Future<void> show(WidgetTester tester, _Bridge bridge) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [runtimeBridgeProvider.overrideWithValue(bridge)],
        child: MaterialApp(
          theme: buildLightTheme(),
          locale: AppLocale.en,
          supportedLocales: AppLocale.supported,
          localizationsDelegates: const [L10n.delegate, ...GlobalMaterialLocalizations.delegates],
          home: const Scaffold(body: SingleChildScrollView(child: EngineTuningSettings())),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  SettingsTile applyTile(WidgetTester tester, String title) =>
      tester.widget<SettingsTile>(find.widgetWithText(SettingsTile, title));

  testWidgets('a pending accessibility action stays disabled until dangerous actions are allowed', (tester) async {
    final bridge = _Bridge(const [_accessibility]);
    await show(tester, bridge);
    final l10n = lookupL10n(AppLocale.en);

    expect(applyTile(tester, l10n.tuningNothingToDo).onTap, isNull);
    await tester.tap(find.text(l10n.tuningNothingToDo));
    await tester.pumpAndSettle();
    expect(bridge.allowDangerousCalls, isEmpty);
    expect(bridge.accessibilityServices, isEmpty);
  });

  testWidgets('allowing dangerous actions includes accessibility in pending and applies it once', (tester) async {
    final bridge = _Bridge(const [_accessibility])
      ..accessibilityPending = Completer<TuningResult?>();
    await show(tester, bridge);
    final l10n = lookupL10n(AppLocale.en);

    await tester.tap(find.byType(Switch));
    await tester.pump();
    expect(applyTile(tester, l10n.tuningApply(1)).onTap, isNotNull);

    await tester.tap(find.text(l10n.tuningApply(1)));
    await tester.pump();
    expect(bridge.allowDangerousCalls, [true]);
    expect(bridge.accessibilityServices, ['io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService']);
    expect(applyTile(tester, l10n.tuningApply(1)).onTap, isNull);
    expect(tester.widget<SettingsSwitchTile>(find.byType(SettingsSwitchTile)).onChanged, isNull);

    await tester.tap(find.text(l10n.tuningApply(1)));
    await tester.pump();
    expect(bridge.accessibilityServices, hasLength(1));
    bridge.accessibilityPending!.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('a failed accessibility apply shows the failure notice', (tester) async {
    final bridge = _Bridge(const [_accessibility])
      ..accessibilityResult = const TuningResult(
        id: 'accessibilityAuto',
        alreadyApplied: false,
        applied: false,
        reason: 'writeFailed',
      );
    await show(tester, bridge);
    final l10n = lookupL10n(AppLocale.en);

    await tester.tap(find.byType(Switch));
    await tester.pump();
    await tester.tap(find.text(l10n.tuningApply(1)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text(l10n.tuningSomeFailed(1)), findsOneWidget);
  });

  testWidgets('English tuning copy is selected by stable action id instead of native Chinese text', (tester) async {
    TuningItem nativeItem(String id, String title, String detail) => TuningItem(
      id: id,
      title: title,
      detail: detail,
      persistent: true,
      dangerous: id == 'accessibilityAuto',
      applied: true,
    );

    final nativeItems = [
      nativeItem('phantom', '关闭幻影进程杀手', '阻止系统在后台杀掉引擎的 node 子进程。需要重启后仍保持生效。'),
      nativeItem('batteryExempt', '电池白名单', '让系统不把 DSH Go 当作可随时回收的后台应用。'),
      nativeItem('standbyBucket', '待机桶：活跃', '避免限制型 ROM 把应用降速到受限待机桶。'),
      nativeItem('miuiPowerKeeper', 'MIUI 省电豁免', '小米/红米上单独的非 AOSP 后台限制，按 ROM 探测后写入。'),
      nativeItem('accessibilityAuto', '自动恢复无障碍服务', '允许应用在服务被系统关闭后自行恢复。会改变设备的安全姿态，默认不勾选。'),
    ];
    await show(tester, _Bridge(nativeItems));

    const expectedCopy = [
      'Disable phantom process killer',
      "Keeps the system from killing the engine's node child processes in the background; expected to persist across reboots.",
      'Battery whitelist',
      'Prevents the system from treating DSH Go as a background app it can reclaim at any time.',
      'Standby bucket: active',
      'Prevents restrictive ROMs from moving the app into a constrained standby bucket.',
      'MIUI battery exemption',
      "Handles Xiaomi/Redmi's separate non-AOSP background restriction, applied only after detecting it on the ROM.",
      'Restore accessibility service automatically',
      "Lets the app restore its service after the system disables it. This changes the device's security posture and is off by default.",
    ];
    for (final text in expectedCopy) {
      expect(find.text(text), findsOneWidget);
    }
    for (final item in nativeItems) {
      expect(find.text(item.title), findsNothing);
      expect(find.text(item.detail), findsNothing);
    }
  });
}
