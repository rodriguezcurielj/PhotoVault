; Gallery Server installer. Built by build.ps1 (passes VERSION, STAGE and OUT).
Unicode true
SetCompressor /SOLID lzma
!include "MUI2.nsh"

!define APP "Gallery Server"
!define EXE "Gallery Server.exe"
!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\GalleryServer"

Name "${APP}"
OutFile "${OUT}"
InstallDir "$PROGRAMFILES64\${APP}"
InstallDirRegKey HKLM "Software\${APP}" "InstallDir"
; Admin so the firewall can let your phone reach this PC.
RequestExecutionLevel admin
BrandingText "${APP} ${VERSION}"

!define MUI_ICON "${STAGE}\..\..\windows\gallery.ico"
!define MUI_UNICON "${STAGE}\..\..\windows\gallery.ico"
!define MUI_WELCOMEPAGE_TITLE "Install Gallery Server"
!define MUI_WELCOMEPAGE_TEXT "Gallery Server turns this PC into your own private photo cloud. Your phone backs up to it and you can see every photo from anywhere in your home.$\r$\n$\r$\nYour photos stay on this PC. Nothing is uploaded to anyone else."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Start Gallery Server now"
!define MUI_FINISHPAGE_RUN_FUNCTION StartApp
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${APP}"
VIAddVersionKey "FileDescription" "${APP} Setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" ""

Function StartApp
  ; Through Explorer, so the app runs as the user and not as admin.
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\${EXE}"'
FunctionEnd

Section "Install"
  ; An update: close the running copy first (its server closes with it).
  nsExec::Exec 'taskkill /f /im "${EXE}"'
  Sleep 1000
  SetOutPath "$INSTDIR"
  RMDir /r "$INSTDIR\app\src"
  RMDir /r "$INSTDIR\app\public"
  RMDir /r "$INSTDIR\app\node_modules"
  File /r "${STAGE}\*.*"

  CreateShortcut "$SMPROGRAMS\${APP}.lnk" "$INSTDIR\${EXE}"
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${APP}"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="${APP}" dir=in action=allow program="$INSTDIR\node\node.exe" enable=yes profile=any'
  nsExec::Exec 'netsh advfirewall firewall add rule name="${APP}" dir=in action=allow program="$INSTDIR\gallery-tunnel.exe" enable=yes profile=any'

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "Software\${APP}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "${UNINST}" "DisplayName" "${APP}"
  WriteRegStr HKLM "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINST}" "DisplayIcon" "$INSTDIR\${EXE}"
  WriteRegStr HKLM "${UNINST}" "Publisher" "Gallery"
  WriteRegStr HKLM "${UNINST}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKLM "${UNINST}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINST}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /f /im "${EXE}"'
  Sleep 1000
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${APP}"'
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${APP}"
  Delete "$SMPROGRAMS\${APP}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "${UNINST}"
  DeleteRegKey HKLM "Software\${APP}"
  MessageBox MB_OK|MB_ICONINFORMATION "Gallery Server was removed. Your photos were not deleted; they're still in the folder you picked."
SectionEnd
