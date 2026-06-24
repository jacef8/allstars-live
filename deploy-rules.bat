@echo off
REM Deploy Firestore + Cloud Storage security rules to the allstars-live project
REM (reference/web-scoring/firestore.rules + storage.rules). Run AFTER `firebase login`.
REM NOTE: the Storage part needs Cloud Storage initialized ONCE in the Firebase
REM console (Build -> Storage -> Get started) — otherwise the storage deploy errors.
cd /d "%~dp0"
"C:\Program Files\nodejs\node.exe" "C:\Users\jford\AppData\Roaming\npm\node_modules\firebase-tools\lib\bin\firebase.js" deploy --only firestore:rules,storage
echo.
echo ---- done (read the result above) ----
pause
