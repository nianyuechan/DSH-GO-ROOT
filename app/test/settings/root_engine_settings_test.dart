import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:dsh_app/ui/settings/diagnostics_page.dart';
import 'package:dsh_app/ui/settings/root_engine_settings.dart';
import 'package:dsh_app/ui/settings/settings_widgets.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../shell/golden_harness.dart';
import '../support/test_fonts.dart';

class _Runtime extends LocalRuntimeController {
  _Runtime(this.initial);
  final RuntimeStatus initial;
  @override
  LocalRuntimeState build() => LocalRuntimeState(status: initial, loaded: true);
}

class _Bridge extends RuntimeBridge {
  final changes = <bool>[];
  @override
  Future<void> setRootEngine(bool enabled) async => changes.add(enabled);
}

void main() {
  Future<void> showSettings(WidgetTester tester, RuntimeStatus status, _Bridge bridge) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          localRuntimeProvider.overrideWith(() => _Runtime(status)),
          runtimeBridgeProvider.overrideWithValue(bridge),
        ],
        child: MaterialApp(
          theme: withTestFonts(buildLightTheme()),
          locale: AppLocale.zhCN,
          supportedLocales: AppLocale.supported,
          localizationsDelegates: const [L10n.delegate, ...GlobalMaterialLocalizations.delegates],
          home: const Scaffold(body: SingleChildScrollView(child: RootEngineSettings())),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  SettingsSwitchTile tile(WidgetTester tester) => tester.widget<SettingsSwitchTile>(find.byType(SettingsSwitchTile));

  testWidgets('tool support alone enables the first root entry before a grant is cached', (tester) async {
    final bridge = _Bridge();
    await showSettings(
      tester,
      const RuntimeStatus(phase: RuntimePhase.installed, rootEngineSupported: true),
      bridge,
    );

    expect(tile(tester).onChanged, isNotNull);
    expect(tile(tester).subtitle, lookupL10n(AppLocale.zhCN).phoneRootHint);
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(bridge.changes, [true]);
  });

  testWidgets('an old native status without root facts fails closed', (tester) async {
    await showSettings(tester, RuntimeStatus.fromMap(const {'phase': 'installed'}), _Bridge());

    expect(tile(tester).onChanged, isNull);
    expect(tile(tester).subtitle, lookupL10n(AppLocale.zhCN).phoneRootUnavailableHint);
  });

  Future<void> showDiagnostics(WidgetTester tester, RuntimeStatus status) async {
    tester.view.physicalSize = const Size(500, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      goldenApp(
        home: const DiagnosticsPage(),
        brightness: Brightness.light,
        overrides: [
          localRuntimeProvider.overrideWith(
            () => FakeRuntime(LocalRuntimeState(status: status, loaded: true)),
          ),
          settingsProvider.overrideWith(() => FakeSettings(const AppSettings(loaded: true))),
          localRuntimeLogsProvider.overrideWithBuild((ref, notifier) => const []),
        ],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
  }

  testWidgets('diagnostics shows the root area for supported tools without a cached grant', (tester) async {
    await showDiagnostics(
      tester,
      const RuntimeStatus(phase: RuntimePhase.installed, rootEngineSupported: true),
    );

    expect(find.byType(RootEngineSettings), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('diagnostics hides the root area for an old native status', (tester) async {
    await showDiagnostics(tester, RuntimeStatus.fromMap(const {'phase': 'installed'}));

    expect(find.byType(RootEngineSettings), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
