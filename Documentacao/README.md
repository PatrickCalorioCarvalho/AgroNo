
cd .\Documentacao\
npm run build 
cd build 
git init 
git checkout -b gh-pages 
git add . 
git commit -m "Deploy" 
git remote add origin https://github.com/PatrickCalorioCarvalho/AgroNo.git 
git push -f origin gh-pages