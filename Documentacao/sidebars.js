/**
 * Creating a sidebar enables you to:
 - create an ordered group of docs
 - render a sidebar for each doc of that group
 - provide next/previous navigation

 The sidebars can be generated from the filesystem, or explicitly defined here.

 Create as many sidebars as you want.
 */

// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  tutorialSidebar: [
    'intro',
    'architecture',
    {
      type: 'category',
      label: 'Nós IoT',
      items: [
        'nodes/index',
        'nodes/weather-station',
        'nodes/insect-trap',
        'nodes/soil-sensor',
        'nodes/drone-mapping',
      ],
    },
    'gateway',
    'server-api',
    'drone-pipeline',
    'pesquisa-drone',
    'teoria-mapeamento',
    'development',
  ],
};

export default sidebars;