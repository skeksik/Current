// The app's line icons, the same paths as core/designsystem's CurrentIcons: a 24-unit grid, stroked in the text colour.

const paths = {
  home: 'M4 10.5 12 4l8 6.5M6 9v11h12V9M10 20v-6h4v6',
  settings: 'M4 6h16M4 12h16M4 18h16M7 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0M13 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0',
  server: 'M4 5h16v6H4zM4 13h16v6H4zM7 8h1M7 16h1',
  back: 'M19 12H5M11 6l-6 6 6 6',
  add: 'M12 5v14M5 12h14',
  alert: 'M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0M12 7.5V13M12 16.2v.2',
};

export type IconName = keyof typeof paths;

export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={paths[name]} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
