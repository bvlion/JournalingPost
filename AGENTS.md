# Repository Guidelines

## プロジェクト概要

JournalingPost は、Kotlin / Jetpack Compose で実装された Android の日記投稿アプリです。

- `app/`: Androidアプリ本体

Java 17とリポジトリ同梱のGradle Wrapperを使用します。

## プロダクト原則

- 最優先は「簡単で続くこと」。
- Moodは1タップで記録が成立することを基本とする。
- 文章入力を必須にしない。
- Mood記録・任意追記・振り返りに直接寄与しない機能を安易に増やさない。
- 高機能Mood Trackerや多機能日記アプリを目指さず、機能追加より引き算を優先する。

## 開発方針

- 依頼またはIssueのscopeに必要な変更へ集中し、無関係な変更を同じPull Requestへ混ぜない。
- 実装前に関連する既存コード、呼び出し元、既存テストを確認する。
- 仕様が不明確で複数の妥当な実装がある場合は、推測で決めず実装前にユーザーへ確認する。
- 確定済みの仕様や外部契約を、実装都合だけを理由に変更しない。

## コーディング規約

- `.editorconfig`に従う。
- 依存関係のバージョンは`gradle/libs.versions.toml`で管理する。
- productionコードの識別子は通常のKotlin命名規則に従い、日本語化しない。
- アプリ側で定義するユーザー向け文言はAndroid string resourcesで管理する。
- アプリ内画面での操作に対する一時的な結果通知はSnackbarを基本とする。App Widget、通知、background処理等では、そのsurfaceに適した手段を使う。

### コメント

- コメントやKDocは必要な場合に限って追加する。
- コードだけでは残せない理由、制約、意図が将来の変更判断に必要な場合に記述する。
- クラス名、関数名、変数名や直後の処理を言い換えるだけのコメントやKDocは書かない。
- 記述する場合は日本語とする。

### テスト関数名

- `@Test`のテスト関数名はKotlinのbacktick形式を使い、日本語で記述する。
- `@Test`以外の識別子は通常のKotlin命名規則に従う。

## ビルドとテスト

- 変更後は`git diff --check`を実行する。
- documentationやrepository運用設定のみの変更では、Gradleによる検証は不要。
- Androidのproductionコード、テスト、build設定等へ影響する変更では原則以下を実行する。
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
- release / R8 / ProGuard等へ直接影響する変更では、必要に応じて`./gradlew :app:assembleRelease`を実行する。
- 実行できなかった検証や失敗した検証を成功扱いにしない。

## UI確認

- AIエージェントは実機、エミュレーター、adb、UI automationを使用してアプリのUI操作や目視確認を行わない。
- UIの見た目や操作感はユーザーが実機で確認する。
- UI確認が必要な場合は、ユーザーが確認すべき項目を報告する。

## 秘密情報

- `local.properties`、署名情報、APIキー、アクセストークン等の秘密情報を新規作成、編集、commit、log出力しない。
- 必要な秘密情報が環境にない場合は、ダミー値や設定変更で迂回せず、実行できなかった作業として報告する。

## Git と Pull Request

- `main`へ直接commit / pushせず、専用branchとPull Requestを使用する。
- Pull Request本文には変更内容と検証結果を記載し、対応Issueがある場合はそれも記載する。
- ユーザーの承認なしにPull Requestをマージしない。

## Review

- Claude / Codex等へ実装やreviewを依頼した場合、後から確認する必要のある作業結果や判断はGitHub上に残す。指示内容を言い換えただけの報告は残さない。
- 作業中に確認できる既存のreview threadは、判断済みのまま未resolvedで放置しない。
- 指摘へ対応した場合は、対応内容を簡潔に返信してresolveする。
- 対応不要と判断した場合は、理由を簡潔に返信してresolveする。
- ユーザー判断が必要な場合はresolveせず報告する。
- 権限や利用可能なツールの制約で返信・resolveできない場合は、その旨を報告する。
- 新しいreviewの到着をsleepやpollingで待たない。

reviewのseverityをそのまま修正優先度として扱わない。

まず、通常の人間操作だけで再現できるかを確認する。

高速操作、狭いrace window、特殊な端末状態、I/O障害、fault injection、テスト用Fake等が必要な場合は、その条件と現実の発生可能性を明示する。

論理的に到達可能、またはunit testで再現可能というだけではproduction修正の理由にしない。

発生頻度、実害、不可逆性、修正による複雑化、新規不具合リスクを比較して対応を判断する。

security、privacy、データ破損・喪失等は、低頻度でも被害の大きさを考慮する。
