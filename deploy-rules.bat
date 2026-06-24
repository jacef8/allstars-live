@echo off
REM Deploy Firestore security rules from reference/web-scoring/firestore.rules
REM to the allstars-live project (set in .firebaserc). Run AFTER `firebase login`.
cd /d "%~dp0"
"C:\Program Files\nodejs\node.exe" "C:\Users\jford\AppData\Roaming\npm\node_modules\firebase-tools\lib\bin\firebase.js" deploy --only firestore:rules
echo.
echo ---- done (read the result above) ----
pause
