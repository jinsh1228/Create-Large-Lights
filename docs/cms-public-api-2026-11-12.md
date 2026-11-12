# CMS公開APIへの移行

2026-11-12。CLL変更前: `7e01483df9019ef7d7a4fcdf70b5794a777efcb0`。CMSの対応コミット: `a7490922ea23bbf50aa1a39e3cd29b027c8b5ae0`（1.4.6）。

CMSの内部メソッドやフィールドへMixinする連携を、CMSが提供する公開APIへの登録・呼び出しへ移した。通常配布物のCMSクラス向けMixinは0件。

## 移行内容

| 用途 | 使用する公開API |
|---|---|
| CLLの光線判定・透過部分、照射面の細分化 | `FixtureOptics` |
| ブロック・区画・チャンク・ワールドの変更通知 | `WorldChanges` |
| 制御卓ページと操作領域 | `ConsolePage`・`ConsoleSurface` |
| 制御卓の設定参照・操作送信・CLL用追加設定 | `ConsoleView`・`GroupView`・`ConsoleSetting` |
| 音声・映像の解析データ | `AudioLevels` |
| Sableの座標変換・構造物判定 | `SubLevels`・`LightRays` |
| Irisの使用状態 | `Shaders` |

`LightingRaycastMixin`と`LightingSurfaceScanMixin`を削除。検証用のCMS描画クラス向けMixinと、CMS内部の描画状態を読むリフレクションも削除した。

APIの光線判定は、nullが「CMSに判定を任せる」、MISSが「独自判定で何も当たらなかった」という区別を持つ。透明部分を抜けた光にCMSの標準判定が再適用されないよう、明示的なMISSを返す。

ModelData更新は公開APIで通知し、その際はAPIが要求する描画スレッドへ渡す。API自身が隣接面を含めるため、通知元で余分に範囲を広げない。

Minecraft・Flywheel・CLL自身へのMixinは8件残る。照明の基底クラス・リンク、レンズ・表面描画のフォールバック、サーバーでのフェード設定など、公開APIに対応口がない部分はCMSが互換性保持の対象としている旧インターフェイスを使う。これらもCMSの`ApiCompatibilityTest`で確認した。

照度計算・GPU投影・共有形状のアルゴリズムと投影シェーダーは変更していない。直接Sable型を使うコンパイル依存は削除したが、CMSの間接依存を解決するMavenリポジトリは必要なため残す。

## 対応範囲

照明・SableのAPIはCMSが実験段階としているため、APIの案内に従い、実行時の依存範囲を **CMS 1.4.6以上、1.5未満** とする。ビルドではバージョン番号だけでなく`cms_revision`に指定したソースのコミットを確認する。

CMS公開APIの互換性規約: [api/package-info.java](https://github.com/haruyq/Create-Media-Systems/blob/a7490922ea23bbf50aa1a39e3cd29b027c8b5ae0/src/main/java/org/haruyq/createmediasystems/api/package-info.java)。

作業開始時の最新は`771b43f7296c2cf2356fa33ca17d7d57080686b3`。作業中に`a7490922ea23bbf50aa1a39e3cd29b027c8b5ae0`へ更新されたため追従した。両コミット間で公開API・照明パッケージ・WorldEventsに差分がないことを確認し、新しいコミットでもビルド・API互換性・描画と照度を再検証した。

CMSの通信プロトコルはこの間に23から24へ変更されている。同じ1.4.6という表示でも、クライアントとサーバーでCMSのビルド（コミット）を揃える必要がある。

## 検証

最新の`a7490922ea23bbf50aa1a39e3cd29b027c8b5ae0`で:

- `./gradlew --no-daemon clean build` 成功。
- CLLユニットテスト80件、失敗・スキップ0。CMSクラス向けMixinの再混入防止と、公開API経由のキャッシュ更新も含む。
- CMSのAPI互換性テスト3件実行、失敗0。既存リンク・公開APIの存在と記録を検証。ベースラインを書き出す補助テスト1件は既定どおりスキップ。
- 実ゲームの描画・照度102項目、失敗0。特殊形状、ModelData更新、遮蔽物、向き、色、フェード、Flywheel、資源の再読込と退出時の解放を含む。

公開API・照明処理が同一の`771b43f`で移行直後に行った追加検証:

- 制御卓・シーン・フェード・タイマー・街灯など57項目、失敗0。
- 128灯の実照度有効な性能シーンで全灯をGPU描画。view=1のフレーム中央値11.048ms、GPU投影中央値5.429ms、静止時の影更新0/frame。
- 64灯・3視点で従来の独立した1灯用シェーダーとRGBA32Fバッファを比較し、各視点3,680,640成分で許容誤差超過0、OpenGLエラー0。許容誤差は `0.0001 + abs(reference) * 0.00002`。
- CMS側の描画を使う単灯・3視点も正常終了。
- 透過・遮蔽の3,000本の光線照合、点灯・消灯後の区画内4,096ブロックとサーバー照度との一致を確認。

性能シーンはRTX 4060、NVIDIA 595.99.02 / OpenGL 4.6、起動設定1280×720、VSync無効、上限260fps、128灯・射程256・照射角80°。全modの特殊形状、実際のIrisパックやSable構造物を網羅した検証ではない。

ログは`cms-public-api-*.log`、集計は`cms-public-api-results.json`と`cms-public-api-verification.json`に保存。

## CMSコミット確認

作業前（2026-11-12T14:55:00.313366+00:00）:

- ローカル・実依存: `05b8d4f7d734b69d23aa1ef9ba4f0904b7baf367`
- リモートHEAD: `771b43f7296c2cf2356fa33ca17d7d57080686b3`

作業後（2026-11-12T15:38:35.164749+00:00）:

- ローカル・実依存・リモートHEAD: `a7490922ea23bbf50aa1a39e3cd29b027c8b5ae0`
- CMS checkoutに変更なし。

## 配布物

`releases/create_large_lights-0.1.0.jar`: 277,374 bytes。

JARに旧CMS向けMixin・検証コードが含まれないこと、CMS-Commit・実行時の依存範囲・本番リソースを確認。

SHA-256: `2ebe876d3e5f923d13f143ba8912f9d4120eab300ff808fcd4a41221d19e635c`
