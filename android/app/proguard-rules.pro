# DAY MVP-0のProGuard/R8ルール。
# MediaPipe Tasks Vision はネイティブ経由でリフレクションを使うため、release shrink時に消えないよう保持する。
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Room生成コードはKSPが処理するため通常は追加ルール不要。将来annotationベースの型を増やしたらここに追記する。

# MediaPipe が使うロガー(Flogger)は、難読化すると呼び出し元をスタックから探せず、
# release 起動時の MediaPipe 初期化で IllegalStateException("no caller found on the stack") になる。
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# MediaPipe が使う protobuf-lite は、フィールド名をリフレクションで引く。難読化すると
# RuntimeException("Field typeUrl_ for com.google.protobuf.Any not found") で落ちる。
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
