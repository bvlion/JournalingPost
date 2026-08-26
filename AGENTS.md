# Repository Guidelines

## プロジェクト概要

JournalingPost は、Kotlin/Jetpack Compose で実装された Android 単一モジュールのシンプルな日記投稿アプリです。

- `app/`: Androidアプリ本体（UI・ViewModel・Webhook投稿処理）

Java 17と、リポジトリ同梱のGradle Wrapperを使用します。

## プロダクト原則

- 最優先は「簡単で続くこと」。
- Moodを最低1タップで記録成立させる方向を守る。
- 文章入力を必須にしない。
- 機能追加より引き算を優先する。
- Mood記録・任意追記・振り返りに直接寄与しない機能を安易に増やさない。
- 高機能Mood Tracker化、多機能日記アプリ化を目的にしない。

## 開発方針

- Issueのscopeに必要な最小差分にする。
- 無関係なリファクタリング、命名変更、整形、依存更新を混ぜない。
- 実装前に既存コード・呼び出し元・既存テストを確認する。
- 挙動を推測だけで変更しない。
- 不要なlibrary / frameworkを追加しない。DI framework（Hilt/Koin等）やmulti-module化は現状の規模では原則不要とする。
- アーキテクチャを目的化しない。`domain/usecase/repository/mapper`等の層は、実際の責務が生まれるまで形式的に作らない。
- 要件が不明確で複数の妥当な実装がある場合は、実装前に確認する。
- secretやlocal.propertiesの値をcommit・logへ出さない。
- 変更に対応するテストを追加・更新する。

## コーディング規約

`.editorconfig`に従い、KotlinとGradle Kotlin DSLのインデントはスペース2個とします。

- クラス、型、Compose関数: `UpperCamelCase`
- 関数、プロパティ: `lowerCamelCase`
- 定数: `UPPER_SNAKE_CASE`
- パッケージ: `info.bvlion.journalingpost...`

依存関係のバージョンは`gradle/libs.versions.toml`で管理します。既存ファイルのスタイルを維持し、変更対象外のコードを一括整形しないでください。

コードコメントやテスト内の補足コメントを追加・変更する場合は、日本語で記述してください。自明な処理を説明するだけのコメントは追加せず、実装理由や制約など「なぜ」がコードだけでは分かりにくい場合に限ってコメントしてください。

## ビルドとテスト

変更範囲に対応する、最小限かつ十分な検証を実行してください。release buildのみで顕在化する互換性問題が過去にあったため、debug/releaseの両方を標準検証に含めます。

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:assembleRelease`
- `./gradlew :app:lintDebug`
- `git diff --check`

検証を実行できなかった場合や失敗した場合は、成功したものとして扱わず、実行コマンドと理由を報告してください。

## UI操作

- AIエージェントは、実機・エミュレーター・adb・UI automationを使用したアプリのUI操作を行わないでください。
- UI変更であっても、スクリーンショット取得や目視確認のためにアプリを起動・操作しないでください。
- UIの見た目・操作感の確認はユーザーが行います。必要な確認項目がある場合は、実行せずに確認項目として報告してください。
- 依頼文に実機確認、エミュレーター確認、スクリーンショット取得、UI操作などの指示が含まれていた場合も、そのまま実行しないでください。このAGENTS.mdの制約と競合することをユーザーへ確認してください。
- ビルド、unit test、lint、`git diff --check`など、UI操作を伴わない自動検証は通常どおり実行してください。
- UI確認を目的としたinstrumented test、emulator操作、adb操作を、代替手段として勝手に追加しないでください。

## 秘密情報とローカル設定

- 秘密情報やローカル専用設定を新規作成、編集、コミットしないでください。
- `local.properties`（Webhookの`POST_URL`/`TEAM_ID`/`TOKEN`/`CHANNEL`/`USER`等）、署名情報、APIキー、アクセストークンを差分へ含めないでください。
- 必要な秘密ファイルが環境に存在しない場合、ダミーファイルの作成やビルド設定の迂回を行わず、実行できなかった検証として報告してください。
- `.gitignore`を変更して秘密ファイルを追跡対象にしないでください。

## Git と Pull Request

- `main`へ直接コミットまたはpushしないでください。
- force pushや`main`ブランチの削除を行わないでください。
- 作業ごとに専用ブランチを使用してください。
- コミット件名は簡潔に記述してください。
- Issue対応を依頼された場合は、調査、実装、検証、コミット、pushを行い、Pull Requestを作成してください。
- Pull Request本文には対応Issueを記載し、目的または原因、変更内容、最終的な検証結果を記載してください。
- 影響のある未実施または失敗した検証が残る場合はPull Request本文に記載し、実行できなかった検証を成功扱いにしないでください。
- UI変更でスクリーンショットや実機確認が有用な場合は、AIエージェント自身では取得・操作せず、ユーザーが確認すべき項目をPull Request本文または完了報告に記載してください。
- ユーザーの承認なしにPull Requestをマージしないでください。
- Issueを手動でcloseせず、タグやReleaseを作成しないでください。
- 破壊的操作、追加の認証、依頼範囲外の変更が必要な場合は、実行前にユーザーへ確認してください。
- scope外の変更（無関係なrefactor / rename / format / dependency update）をPull Requestへ混ぜないでください。気づいた改善案は別Issue候補として報告してください。
- Pull Requestを自動Approveしないでください。
