# Repository Guidelines

## プロジェクト概要

JournalingPost は、Kotlin / Jetpack Compose で実装された Android の日記投稿アプリです。

- `app/`: Androidアプリ本体（UI・ViewModel・Journal・解析・Webhook・Widget等）

Java 17と、リポジトリ同梱のGradle Wrapperを使用します。

## プロダクト原則

- 最優先は「簡単で続くこと」。
- Moodを最低1タップで記録成立させる方向を守る。
- 文章入力を必須にしない。
- 機能追加より引き算を優先する。
- Mood記録・任意追記・振り返りに直接寄与しない機能を安易に増やさない。
- 高機能Mood Tracker化、多機能日記アプリ化を目的にしない。

### プロダクトの単純さと実装の単純さを混同しない

「機能を増やさず、簡単に使えるプロダクトにする」ことは、ライブラリ・標準的なAndroidアーキテクチャ・責務分離まで削ることを意味しません。

- プロダクトの引き算を理由に、保守性・安全性・一般的なAndroid実装慣習まで犠牲にしない。
- library / frameworkを使わないこと自体を目的にしない。
- 標準的な仕組みを避けた結果、Factory、Service Locator、singleton Store、状態同期、generation counter等を独自実装する必要が生じるなら、既存構造を維持する前に設計を見直す。
- AndroidX / Jetpackや広く利用されているlibraryが標準的に解決している問題を、明確な理由なく独自実装しない。
- 逆に、library・layer・moduleを導入すること自体も目的にしない。導入により削減できる独自コード、責務の明確化、テスト容易性、保守コストと、導入コストを比較して判断する。

## 開発方針

- Issueのscopeに必要な変更へ集中する。
- 無関係なリファクタリング、命名変更、整形、依存更新を同じPRへ混ぜない。
- ただし、要求された変更を安全に実現できない原因が既存構造そのものにある場合は、症状への局所的なworkaroundを重ねる前に構造上の問題を報告する。
- 実装前に既存コード・呼び出し元・既存テストを確認する。
- 挙動を推測だけで変更しない。
- 要件が不明確で複数の妥当な実装がある場合は、実装前に確認する。
- secretやlocal.propertiesの値をcommit・logへ出さない。
- 変更に対応するテストを追加・更新する。
- Issueやreviewの記述を実装前提として機械的に受け入れず、その機能・互換性自体が現在必要かを先に確認する。
- 公開前の自分用開発環境だけを守るmigration・互換層は原則作らない。再インストール、データリセット、手動再設定で十分ならそちらを選ぶ。
- 手動解析の成立確認前に、自動scheduler / Worker / background処理を同じ作業へ先回りして追加しない。ただし、実装順を後ろへ送ることと、決定済みの最終仕様を撤回することを混同しない。
- 決定済みの仕様や外部契約を、実装量削減・単純化・「今は使わない」ことだけを理由に削除・変更しない。仕様自体を変える可能性がある場合は、Issueを書き換えたり実装を始めたりする前にユーザーへ確認する。
- UIから撤回した操作や将来用capabilityを、利用中の呼び出し元がないままrepository等へ残さない。

### Architecture / DI

- DI frameworkの使用をリポジトリ規模だけを理由に禁止しない。
- constructor injectionを基本とし、依存関係の生成・scope・共有を明示する。
- 複数のViewModelFactory、手動`initialize()`、Service Locator、独自singleton Store等が増え、依存関係の組み立て自体がアプリコードの複雑性になっている場合は、Hilt等の標準的なDI手段を含めて見直す。
- `ViewModelProvider.Factory`を使う場合は、そのFactoryが本当に最小で分かりやすい解決になっているか確認する。DI frameworkを避けるためだけにFactoryを増やさない。
- `domain` / `usecase` / `repository` / `mapper`等のlayer名を形式的に増やさない。ただし、実際に別責務が存在する場合は「小さいアプリだから」という理由だけで1クラスへ押し込めない。
- multi-module化を規模だけで禁止しないが、明確な依存境界・build上の利点・所有責務がない状態で形式的に分割しない。

## State / Compose

### Stateの所有者

1つの事実に対して、原則としてsource of truthを1つにします。

- 永続データのsource of truthはRepository / DataStore / Room等のデータ層に置く。
- 画面全体の非同期状態・業務状態はViewModelが所有する。
- 入力途中の文字列、ダイアログ開閉、展開状態など、そのComposableだけで完結するUI状態はComposableで所有してよい。
- 構成変更後も維持する必要があるlocal UI stateには`rememberSaveable`を検討する。
- 同じ事実をViewModelのStateFlowとComposableの`mutableStateOf`等へ重複して保持しない。
- 表示のために導出できる値は、別のmutable stateとして同期させる前に、元のstateから導出できないか確認する。

### 整合性

複数の値が同時に1つの画面状態を表す場合、それぞれを独立したStateFlow / Booleanとして更新した結果、存在してほしくない組み合わせが発生しないか確認します。

- 同時に整合している必要がある値は、必要に応じて1つの`UiState` / sealed type / data classとして表現する。
- `Loading` / `Ready` / `Unavailable`等の排他的状態は、複数Booleanの組み合わせより型で表現することを優先する。
- `null`に特別な失敗種別など複数の意味を持たせない。状態として意味が異なるなら型で区別する。
- 非同期更新元が異なる複数stateをCompose側で組み合わせる場合、各stateが更新される時間差で一時的に誤ったUIが描画されないか確認する。
- operationの入力値や結果オブジェクトが既に持っている情報を、Snackbar等の表示用として別stateへ複製しない。

### Stateと一時的な結果

「現在の状態」と「1回の操作結果」を区別します。

- loading中か、入力内容は何か、といった継続的な事実はstateとして扱う。
- 成功・失敗・navigation request等の一時的な結果は、画面離脱中にも保持すべきか、1回だけ伝えればよいかを先に決める。
- 一時結果をBooleanのStateFlowとして増やし、`consumeXxx()`を多数追加することを既定の実装にしない。
- 画面を離れて戻った後にも結果を利用者へ伝える必要がある場合は、その結果自体を必要なcontextとともに保持し、表示後に明示的に消費する設計としてよい。
- operation stateとresult notificationを1つのenumへ詰め込んだため、別surface側で過去の結果を無視するための追加stateが必要になっている場合は、state設計を見直す。

### 非同期処理

- coroutineのcancel、Flow operator、lifecycle等で自然に表現できる処理を、generation counterや複数のpending flagで独自管理しない。
- generation / request id等が本当に必要な場合は、どの古い非同期結果を無効化するためなのかコードから明確にする。
- 非同期処理の競合を修正する際は、論理的なraceが存在するだけで複雑な状態機械を追加しない。通常の利用操作での再現性と実害も確認する。

### ComposeでのFlow購読

- Activity / Compose画面からViewModelのFlowを購読する場合は、画面lifecycleに応じてcollectionを停止できる仕組みを優先する。
- AndroidXの標準手段で実現できる場合は、`collectAsStateWithLifecycle()`等のlifecycle-aware APIを使用する。
- libraryを増やさないことだけを理由に、lifecycle-awareな標準APIを避けない。

## UI / Compose実装

- Material3等のcomponentが提供するslot・state・layout contractを確認し、標準的な拡張ポイントを使う。
- 既存componentの外側へ後付けのTextや固定paddingを置き、見た目だけ合わせる前に、component自身のslotで表現すべき内容か確認する。
- visual bugを修正するときは、まずState遷移とlayout構造を確認する。固定height、Spacer、padding、alpha、animation等で症状だけを隠さない。
- conditionによって要素が出入りするとき、意図しないlayout shift・ちらつき・一瞬だけ誤った状態が描画されないか確認する。
- recomposition自体を問題視しない。問題がある場合は、recompositionによって評価されるstateの組み合わせやState更新順序を確認する。
- UIを実機確認へ回す前に、コード上でcomponentの使い方・State遷移・layout構造が妥当かを確認する。
- 見た目の好みや細かなUI構成について複数の妥当案がある場合、product仕様として決まっていない部分まで固定値や独自構成で作り込まない。

## Hosted解析の固定契約

Hosted解析を扱う場合は、以下を現在の前提とする。実装詳細を簡略化するためにこの契約自体を変更しない。

- JournalEntry / AnalysisResultの原本はAndroid端末に置き、Serverへ恒久保存しない。
- 手動・自動とも解析開始の主体はAndroidとする。
- timezone / recurrence / 次回実行判断はAndroid側で管理する。
- 自動解析の指定時刻は厳密保証ではなく「その時刻ごろ」として扱う。
- Hostedの自動解析は1日1回までとする。
- Hosted ServerのAndroid向けAPIは原則 `POST /v1/installations` と `POST /v1/analyses` の2つとする。
- `POST /v1/installations` で匿名installationをServer内部に作り、AndroidへBearer API keyを返す。AndroidはServer内部installation IDを保持せず、API keyだけを継続利用する。
- `POST /v1/analyses` は同期HTTP request / responseを現在の完成形候補とし、成功時は `200` response bodyで解析結果を受け取ってAndroid側AnalysisResultへ保存する。
- network timeout等による同一解析のretryでは同じidempotency keyを利用し、Server側の期限付きretry result bufferから同じ結果を返せる契約とする。
- Hosted契約ではFCM token、`triggerAt`、ScheduledTrigger、Server側Push予約・schedulerを使用しない。
- 自動解析後の通知が必要な場合はAndroid側のローカル通知を利用する。
- 同期HTTPが実測上成立しない場合だけ非同期化を再検討し、その場合もFCM方式を自動的に復活させない。
- まず手動Hosted解析を接続して実動確認してからAndroid側自動解析へ進むが、これは自動解析を仕様から外す意味ではない。

## コーディング規約

`.editorconfig`に従い、KotlinとGradle Kotlin DSLのインデントはスペース2個とします。

- クラス、型、Compose関数: `UpperCamelCase`
- 関数、プロパティ: `lowerCamelCase`
- 定数: `UPPER_SNAKE_CASE`
- パッケージ: `info.bvlion.journalingpost...`

依存関係のバージョンは`gradle/libs.versions.toml`で管理します。既存ファイルのスタイルを維持し、変更対象外のコードを一括整形しないでください。

productionコードの識別子（クラス、関数、プロパティ、定数等）は上記のKotlin命名規則に従い、日本語化しません。

### UI文言とフィードバック

- ユーザーへ表示される文言（画面タイトル、ボタン、ラベル、説明、空状態、ダイアログ、validation / error / success message、Snackbar、accessibilityの`contentDescription`等）はAndroid string resourcesで管理し、Kotlin / Composeへ直接書きません。ユーザーに表示されない内部識別子・テストデータ・protocol値・JSON key、Compose `@Preview`内のサンプルデータは対象外です。
- Activity / Compose画面上で利用者が操作し、その画面で結果を返すケースの一時的な成功・失敗・エラーfeedbackはSnackbarを基本とします。Toastや、操作結果として画面内に残るTextを混在させません（画面の恒常的な説明・空状態・入力欄自体の状態は「一時feedback」ではなく対象外）。
- App Widget、通知、background処理などSnackbarが自然でないsurfaceには、そのsurfaceに適したfeedbackを使います（アプリ内画面のSnackbar方針を機械的に適用しません）。

### コメント

コメントは原則として「処理内容の説明」には使いません。クラス名・関数名・変数名を軽く追えば処理内容が分かる状態を保ち、コメントはコードだけでは残せない「なぜ」「制約」「意図」を伝える場合に限って書いてください。コメント / KDocを追加・変更する場合は日本語で記述します。

削除・追加しない対象（自明なので書かない）:

- クラス名・関数名・変数名から分かる役割を言い直しているだけのKDoc
- 直後の処理をそのまま日本語で説明しているだけのコメント
- コードを読めばそのまま分かる処理手順の説明
- テストコードで、テスト名・setup・assertionから分かる内容を補足しているだけのコメント
- 将来機能を先回りして説明するだけのコメント

残す・書く対象（「なぜ」がないと将来の変更判断を誤る場合）:

- コメントがないと、将来誤ってリファクタ・簡略化・削除される可能性がある意図や制約
- Android / APIの互換性上、見た目より複雑な実装になっている理由
- 意図的に通常とは異なるエラー処理・fallbackをしている理由
- product上受け入れているtrade-off
- securityやsecretの扱いなど、コードだけでは意図を失いやすい重要な制約
- workaroundなど、実装だけを見て「不要」と判断される可能性があるもの

コメントを残す代わりに、命名や小さなコード整理によって自明にできる場合は、挙動を変えない範囲でそちらを優先してください。ただし、コメント整理のためだけに大規模なリファクタリングをしないでください。

### テスト関数名

`@Test`のテスト関数名はKotlinのbacktick形式を使い、日本語で記述してください。テスト対象・条件・期待結果が自然に分かる名前にします。テスト用helper、Fake、通常のproduction関数等は対象外です。日本語化するのは`@Test`関数名のみとします。

## ビルドとテスト

検証コマンドを慣習的に全部実行するのではなく、「何が壊れる可能性があり、その確認にどの検証が有効か」を変更内容から判断してください。変更範囲に対応する、最小限かつ十分な検証を選んで実行します。

- `git diff --check` は軽量なので、変更内容に関わらず実行して構わない。
- documentationやrepository運用設定（`AGENTS.md`、README等のMarkdown、`.editorconfig`、`.gitattributes`、`.gitignore`等）のみの変更では、Gradleを起動する検証（unit test / lint / assemble）を実行する必要はない。
- Androidのproductionコード・テスト・build設定等に影響する変更のPRでは、原則として以下を実行する。
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
- `./gradlew :app:assembleDebug` は標準検証から外す。debug APKはCI後に配布・利用していないため、通常はビルド不要。
- `./gradlew :app:assembleRelease` は通常のPR作業ごとの標準検証にはしない。release / minify（R8）/ ProGuard設定等に直接関係する変更で、実装中にrelease buildが成立することを確認する価値がある場合は、必要に応じて実行する。
- mainブランチでは、release APKへ影響する変更（productionコード・resource・Manifest・ProGuard / R8設定・依存関係・Gradle設定等）が含まれる場合にCIが`./gradlew :app:assembleRelease`を自動実行する。releaseのみで顕在化する互換性問題を防ぐための最終確認。testコードのみの変更はrelease APKに影響しないため対象外。

検証を実行できなかった場合や失敗した場合は、成功したものとして扱わず、実行コマンドと理由を報告してください。

`.github/workflows/ci.yml`により、pull_requestおよびmainへのpush時に、変更されたパスに応じて上記の検証がGitHub Actions上でも自動実行されます（`git diff --check`は常時実行）。CIでの自動実行はローカル検証を代替するものではないため、両方とも実施してください。

## UI操作

- AIエージェントは、実機・エミュレーター・adb・UI automationを使用したアプリのUI操作を行わない。
- UI変更であっても、スクリーンショット取得や目視確認のためにアプリを起動・操作しない。
- UIの見た目・操作感の確認はユーザーが行う。必要な確認項目がある場合は、実行せずに確認項目として報告する。
- 依頼文に実機確認、エミュレーター確認、スクリーンショット取得、UI操作などの指示が含まれていた場合も、そのまま実行しない。このAGENTS.mdの制約と競合することをユーザーへ確認する。
- ビルド、unit test、lint、`git diff --check`など、UI操作を伴わない自動検証は通常どおり実行する。
- UI確認を目的としたinstrumented test、emulator操作、adb操作を、代替手段として勝手に追加しない。

## 秘密情報とローカル設定

- 秘密情報やローカル専用設定を新規作成、編集、コミットしない。
- `local.properties`（Webhookの`POST_URL` / `TEAM_ID` / `TOKEN` / `CHANNEL` / `USER`等）、署名情報、APIキー、アクセストークンを差分へ含めない。
- 必要な秘密ファイルが環境に存在しない場合、ダミーファイルの作成やビルド設定の迂回を行わず、実行できなかった検証として報告する。
- `.gitignore`を変更して秘密ファイルを追跡対象にしない。

## Git と Pull Request

- `main`へ直接コミットまたはpushしない。
- force pushや`main`ブランチの削除を行わない。
- 作業ごとに専用ブランチを使用する。
- コミット件名は簡潔に記述する。
- Issue対応を依頼された場合は、調査、実装、検証、コミット、pushを行い、Pull Requestを作成する。
- Pull Request本文には対応Issueを記載し、目的または原因、変更内容、最終的な検証結果を記載する。
- 影響のある未実施または失敗した検証が残る場合はPull Request本文に記載し、実行できなかった検証を成功扱いにしない。
- UI変更でスクリーンショットや実機確認が有用な場合は、AIエージェント自身では取得・操作せず、ユーザーが確認すべき項目をPull Request本文または完了報告に記載する。
- ユーザーの承認なしにPull Requestをマージしない。
- Issueを手動でcloseせず、タグやReleaseを作成しない。
- 破壊的操作、追加の認証、依頼範囲外の変更が必要な場合は、実行前にユーザーへ確認する。
- scope外の変更（無関係なrefactor / rename / format / dependency update）をPull Requestへ混ぜない。気づいた改善案は別Issue候補として報告する。
- Pull Requestを自動Approveしない。

### Claude review

productionコードを変更したPull Requestは、マージ候補になったlatest headに対してClaudeによるコードレビューを実施します。

- 実装を担当したClaudeの作業完了報告や自己確認を、コードレビューの代わりにしない。
- latest headの実コードとdiffを対象に、実装意図だけでなくState遷移、Composeの描画・lifecycle、非同期処理、エラー経路、既存仕様との整合性を確認する。
- UI変更では、実機確認へ回す前にcomponentの使い方、Stateの所有者、条件付き描画によるlayout shift、後付けpadding / fixed size等のworkaroundがないかも確認する。
- review結果はGitHubに残す。
- 指摘がなければ、その旨をGitHub上で分かる形にする。
- Claudeを利用できない環境では勝手にreviewを省略せず、ユーザーへ報告する。
- review待ちのsleep / pollingは行わない。レビューを実行できる時点で明示的に実施し、その結果を処理する。

### Pull Request review thread

PR上のClaude / Codex等によるreview threadは、判断済みのまま未resolvedで放置しないでください。作業中にAPI等ですでに取得できる既存のreview threadがある場合、それを未処理のまま放置しないことをルールとします。

一方で、新しいreviewの投稿を待つ挙動はしないでください。

- PR作成後、新しいreviewが投稿されることを待たない。
- review到着確認のためのsleep・polling・定期的な再取得を行わない。
- Codex等のreview投稿を、作業の完了条件にしない。
- 「reviewがまだ来ていないので数分待つ」「過去は数分だったので再確認する」「一定時間だけポーリングしてから終了する」といった挙動は禁止する。
- 作業終了後に新しく投稿されたreviewについて、その作業を行ったAIが待機して処理する必要はない。後からそのPRを確認するAIが対応する。

作業中にすでに存在している既存のreview threadが確認できた場合は、その作業の中で以下のとおり対応してください。

- 指摘に対応してコードを修正した場合
  - 実装した側が、何を修正したか簡潔に返信する。
  - 修正が反映されていることを確認してthreadをresolveする。
- 指摘内容を確認した結果、対応不要と判断した場合
  - 判断した側が、対応しない理由を簡潔に返信する。
  - product上の意図的なtrade-off、scope外、実害がない等の判断理由を残したうえでthreadをresolveする。
- ユーザー判断が必要、または判断がまだ確定していない場合
  - 勝手にresolveしない。
  - 未resolvedのまま、何を判断してほしいか報告する。
- 権限や利用可能なツールの制約で返信・resolveできない場合
  - 黙って残さず、その旨を完了報告に明記する。

### Review findingの判断基準

Claude / Codex等のseverityや提案を理由に機械的に修正しないでください。

review findingごとに、まず「通常の利用者が通常の端末操作を行って再現できるか」を確認します。

- 通常の人間操作だけで再現できる場合は、その具体的な操作手順を確認する。
- 高速操作や短いrace windowが必要なら、どの程度のタイミング条件かを明示する。
- 特殊な端末状態、ストレージ枯渇、I/O障害、network fault、process kill、fault injection、テスト専用Fake等が必要なら、その条件を明示する。
- unit testやFakeで再現できること、論理的に到達可能であることだけを、production修正の根拠にしない。
- 発生頻度、利用者への実害、データ損失・漏洩・不可逆性の有無、復旧可能性を確認する。
- 修正によって増えるState、分岐、同期処理、独自workaround、新規不具合リスクも比較する。
- 再現性が極端に低く実害も限定的で、修正による複雑化の方が大きい場合は、対応しない判断を認める。
- security、privacy、データ破損・喪失等、発生頻度が低くても被害が重大なものは頻度だけで却下しない。

最終的に、既存product方針、Issueのscope、現実の再現可能性、実害、変更リスクを比較して、修正するか対応不要とするかを判断してください。
