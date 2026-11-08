import streamlit as st
import pandas as pd
import folium
from streamlit_folium import st_folium

# Simulação de dados vindos do banco
data = pd.DataFrame([
    {"node_id": 0, "parent_id": None, "lat": -22.120, "lon": -51.390},
    {"node_id": 1, "parent_id": 0, "lat": -22.121, "lon": -51.391},
    {"node_id": 2, "parent_id": 0, "lat": -22.122, "lon": -51.392},
    {"node_id": 3, "parent_id": 2, "lat": -22.124, "lon": -51.395},
])

# Criar mapa centralizado no gateway
gateway = data[data["node_id"] == 0].iloc[0]
m = folium.Map(location=[gateway.lat, gateway.lon], zoom_start=16)

# Adicionar nós
for _, row in data.iterrows():
    folium.Marker(
        [row.lat, row.lon],
        popup=f"Node {row.node_id}\nParent: {row.parent_id}",
        icon=folium.Icon(color="blue" if row.node_id != 0 else "red")
    ).add_to(m)

# Adicionar linhas entre nó e pai
for _, row in data.iterrows():
    if pd.notna(row.parent_id):

        parent_rows = data.query("node_id == @row.parent_id")

        if len(parent_rows) == 1:
            parent = parent_rows.iloc[0]

            folium.PolyLine(
                [[row.lat, row.lon], [parent.lat, parent.lon]]
            ).add_to(m)

st.title("Topologia Mesh IoT")
st_folium(m, width=700, height=500)