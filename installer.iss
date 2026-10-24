; Inno Setup script for DV Converter. build.ps1 compiles this after PyInstaller;
; it packages whatever is in dist\DV Converter\.
#define AppName    "DV Converter"
#define AppVersion "1.0.0"
#define AppExe     "DV Converter.exe"

[Setup]
AppId={{B6F1D3A7-4C58-4E2D-9A31-7C0E2F5A8D41}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Param Patel
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
LicenseFile=LICENSE
SetupIconFile=icon.ico
OutputDir=.
OutputBaseFilename=DV-Converter-{#AppVersion}-setup
; installs per user by default, so no admin password is needed; the user can
; still pick "all users" in the first dialog
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
AppMutex=DVConverterRunning

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "dist\DV Converter\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
