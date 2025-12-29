import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'AgroNó',
  tagline: 'Plataforma IoT Agrícola de Baixo Custo • Offline-First • Open Source',
  favicon: 'img/favicon.ico',
  url: 'https://patrickcaloriocarvalho.github.io',
  baseUrl: '/AgroNo/',
  organizationName: 'patrickcaloriocarvalho',
  projectName: 'AgroNo',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
  },
  
  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
        },
        blog: {
          showReadingTime: true,
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

themes: [ [ '@docusaurus/theme-mermaid', { mermaid: { theme: 'handDrawn' }, }, ], ],

  plugins: [
    [
      'docusaurus-graph',
      {
        minWidth: 600,
        minHeight: 500,
      },
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      navbar: {
        title: 'AgroNó',
        logo: {
          alt: 'AgroNó Logo',
          src: 'img/logo.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'tutorialSidebar',
            position: 'left',
            label: 'Documentacão',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [],
        copyright: `Copyright © ${new Date().getFullYear()} AgroNó`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
      },
    }),
};

export default config;