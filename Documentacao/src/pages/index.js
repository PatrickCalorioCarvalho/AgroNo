import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';

export default function Home() {
  return (
    <Layout
      title="AgroNó"
      description="Plataforma IoT Agrícola de Baixo Custo • Offline-First • Open Source">
      <main style={{ textAlign: 'center', padding: '2rem' }}>
        <img src="/img/logo.svg" alt="AgroNó Logo" style={{ width: '300px', marginBottom: '4rem' }} />
        <h1>Welcome to AgroNó</h1>
        <p>Plataforma IoT Agrícola de Baixo Custo • Offline-First • Open Source</p>
        <Link to="/docs/intro" className="button button--primary button--lg">
          Documentacão
        </Link>
      </main>
    </Layout>
  );
}