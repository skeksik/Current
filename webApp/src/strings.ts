// The app's texts. Mirrors composeUi/src/commonMain/composeResources/values/strings.xml, so desktop and web say the same thing:
// change both together. Keys are the Compose names in camel case; `%1$s` arguments become function parameters.

// Enum names that cross the facade as plain strings (web/ShellPages.kt). ShellPagesTest pins the same lists, so a new Kotlin value
// fails :web:jsNodeTest until it is added here, and `satisfies` below then fails the TypeScript build until it has a text.
export type SecretStorageName = 'CredentialManager' | 'SecretService' | 'Browser' | 'File' | 'Memory';
export type ServerSetupProblemName =
  | 'InvalidAddress'
  | 'Unreachable'
  | 'TimedOut'
  | 'Redirected'
  | 'NotThisService'
  | 'Refused'
  | 'AlreadyAdded'
  | 'DifferentServer'
  | 'ServerNotSaved';
export type AddressScopeName = 'Local' | 'Internet';
export type SignInProblemName =
  | 'WrongCredentials'
  | 'Unreachable'
  | 'TimedOut'
  | 'Refused'
  | 'DifferentServer'
  | 'QuickConnectDisabled'
  | 'QuickConnectExpired'
  | 'ServerNotSaved';

/** The text for an enum [name] from the facade, or [fallback] for a name this file does not know, rather than an empty message. */
export function textFor<Name extends string>(texts: Record<Name, string>, name: string, fallback: string): string {
  return Object.hasOwn(texts, name) ? texts[name as Name] : fallback;
}

export const strings = {
  appName: 'Current',

  navHome: 'Home',
  navSettings: 'Settings',
  navBack: 'Back',

  homeNoServerTitle: 'Add a server to start',
  homeNoServerMessage: 'Current plays your own media from a Jellyfin server. Add its address to sign in.',
  homeAddServer: 'Add a server',
  homeSignedOutTitle: 'You are signed out',
  homeSignedOutMessage: (server: string) => `Sign in to ${server} to see your libraries.`,
  homeSignIn: 'Sign in',
  homeSignedInTitle: (server: string) => `Signed in to ${server}`,
  homeSignedInAs: (user: string, server: string) => `Signed in as ${user} on ${server}. Your libraries will appear here.`,
  homeSignedInMessage: 'Your libraries will appear here.',
  homeOpenSettings: 'Open settings',

  settingsTitle: 'Settings',
  settingsServers: 'Servers',
  settingsAddServer: 'Add server',
  settingsNoServers: 'No servers yet. Add a Jellyfin server by its address.',
  settingsSignedInAs: (user: string) => `Signed in as ${user}`,
  settingsSignedOut: 'Signed out',
  settingsOffline: 'Offline',
  settingsSignIn: 'Sign in',
  settingsSignOut: 'Sign out',
  settingsRemove: 'Remove',
  settingsRemoveTitle: (server: string) => `Remove ${server}?`,
  settingsRemoveMessage: 'You are signed out and the server is forgotten on this device. You can add it again later.',
  settingsCancel: 'Cancel',
  settingsSecrets: {
    CredentialManager: 'Sign-ins are kept in Windows Credential Manager.',
    SecretService: "Sign-ins are kept in your desktop's keyring, through the Secret Service.",
    Browser: 'Sign-ins are kept in this browser.',
    File: 'Sign-ins are kept in a file only your user account can read.',
    Memory: 'No credential store was found, so you are signed out when the app quits.',
  } satisfies Record<SecretStorageName, string>,
  settingsSecretsKeepInFile: 'Keep sign-ins in a file',
  settingsAbout: 'About',
  settingsVersion: (version: string) => `Version ${version}`,
  settingsDiagnosticsHint:
    'Copies the app version and the last requests to your servers, without addresses or passwords, to paste into a problem report.',
  settingsCopyDiagnostics: 'Copy diagnostics',
  settingsDiagnosticsCopied: 'Copied',
  settingsAddresses: 'Addresses',

  addressesTitle: 'Addresses',
  addressesSubtitle: (server: string) => `${server} is tried at these addresses from the top. The first one that answers is used.`,
  addressesAdd: 'Add address',
  addressesScopes: {
    Local: 'Local network',
    Internet: 'Internet',
  } satisfies Record<AddressScopeName, string>,
  addressesInUse: 'In use',
  addressesUnreachable: (server: string) => `${server} does not answer at any of these addresses right now.`,
  addressesMoveUp: 'Move up',
  addressesMoveDown: 'Move down',
  addressesMoveUpDescription: (address: string) => `Move ${address} up`,
  addressesMoveDownDescription: (address: string) => `Move ${address} down`,
  addressesRemoveDescription: (address: string) => `Remove ${address}`,
  addressesKeepOne: 'A server keeps at least one address, so this one cannot be removed.',
  addressesRemoveTitle: (address: string) => `Remove ${address}?`,
  addressesRemoveMessage: 'Current stops trying this address. You can add it again later, while the server answers there.',

  addServerTitle: 'Add a server',
  addServerSubtitle: 'Enter the address of your Jellyfin server, such as media.example.com or 192.168.1.10:8096.',
  addServerAddress: 'Server address',
  addServerCheck: 'Check',
  addServerChecking: 'Looking for the server…',
  addServerFound: (server: string) => `Found ${server}`,
  addServerVersion: (version: string) => `Jellyfin ${version}`,
  addServerLocal: 'On your local network',
  addServerInternet: 'On the internet',
  addServerPlainHttp:
    'This address is not encrypted (http on the internet). Your password and media would cross the internet readable by others. Use https if the server offers it.',
  addServerConfirm: 'Add this server',
  addServerChange: 'Change address',
  addServerProblems: {
    InvalidAddress: 'That is not a server address.',
    Unreachable: 'No server answered. Check the address, the port and that the server is running.',
    TimedOut: 'The server took too long to answer.',
    Redirected: 'The address redirects elsewhere. If the server runs under a path, include it (for example /jellyfin), or use https.',
    NotThisService: 'Something answered, but it is not a Jellyfin server.',
    Refused: 'The server refused the request.',
    AlreadyAdded: 'This server is already added.',
    DifferentServer: 'Another server answers at this address.',
    ServerNotSaved: 'This server is no longer saved.',
  } satisfies Record<ServerSetupProblemName, string>,
  addAddressTitle: 'Add an address',
  addAddressSubtitle: (server: string) =>
    `Enter another address of ${server}, such as media.example.com or 192.168.1.10:8096. It is added only if ${server} answers there.`,
  addAddressConfirm: 'Add this address',
  addAddressAlreadyAdded: 'This address is already added.',
  addAddressServerNotSaved: 'This server is no longer saved.',

  signInTitle: (server: string) => `Sign in to ${server}`,
  signInSubtitle: 'Use your Jellyfin account.',
  signInUsername: 'Username',
  signInPassword: 'Password',
  signInSubmit: 'Sign in',
  signInWorking: 'Signing in…',
  signInQuickConnect: 'Use Quick Connect',
  signInQuickConnectTitle: 'Quick Connect code',
  signInQuickConnectMessage:
    'In another Jellyfin app where you are signed in, open Quick Connect and enter this code. This page continues by itself once you approve it.',
  signInQuickConnectWaiting: 'Waiting for approval…',
  signInQuickConnectCancel: 'Use a password instead',
  signInProblems: {
    WrongCredentials: 'Wrong username or password.',
    Unreachable: 'The server cannot be reached.',
    TimedOut: 'The server took too long to answer.',
    Refused: 'The server refused the sign-in.',
    DifferentServer: 'Another server answers at this address now, so nothing was sent to it.',
    QuickConnectDisabled: 'Quick Connect is turned off on this server.',
    QuickConnectExpired: 'The code expired. Try again.',
    ServerNotSaved: 'This server is no longer saved.',
  } satisfies Record<SignInProblemName, string>,
};

// Web only: no desktop counterpart yet.
export const webStrings = {
  settingsPlayback: 'Playback',
  settingsPlaybackSupport: 'What this browser plays',
  playbackSupportTitle: 'Playback support',
  playbackSupportSubtitle: 'What this browser plays itself, from its probes. The server remuxes or transcodes the rest.',
  playbackSupportProbing: 'Probing…',
  playbackSupportDirect: 'Direct play',
  playbackSupportNoDirect: 'No container plays directly.',
  playbackSupportContainer: 'Container',
  playbackSupportVideo: 'Video',
  playbackSupportAudio: 'Audio',
  playbackSupportCodec: 'Codec',
  playbackSupportRanges: 'Dynamic ranges',
  playbackSupportHdrDisplay: 'HDR display',
  playbackSupportTenBit: '10-bit',
  playbackSupportUhd: '2160p',
  playbackSupportChannels: 'Audio channels',
  playbackSupportHlsCopy: 'Server HLS, copied',
  playbackSupportHlsCopyLists: (video: string, audio: string) => `video ${video}; audio ${audio}`,
  playbackSupportHlsThrough: 'HLS through',
  playbackSupportHlsMse: 'Media Source Extensions',
  playbackSupportHlsNative: 'the browser',
  playbackSupportHlsNone: 'nothing',
  playbackSupportYes: 'yes',
  playbackSupportNo: 'no',
  playbackSupportNone: 'none',
  playbackSupportAnswers: 'Probe answers',
  playbackSupportAnswersHelp: 'In the format of docs/web-probes/, to record this browser as a test fixture.',
  copy: 'Copy',
  copyDone: 'Copied.',
  copySelected: 'The text is selected: press Ctrl+C, or ⌘C on a Mac, to copy it.',
  copyFailed: 'Copying is not available here.',
  notFoundTitle: 'Not found',
  notFoundMessage: 'There is no page at this address.',
  notFoundHome: 'Go home',
  skipToContent: 'Skip to content',
  navLabel: 'Main',
  failureTitle: 'Something went wrong',
  failureMessage: 'The app stopped because of an error. Reload the page to start it again; your servers and sign-ins are kept.',
  failureReload: 'Reload',
  problemFallback: 'Something went wrong. Try again.',
  signInServerMissingTitle: 'Server not found',
};
