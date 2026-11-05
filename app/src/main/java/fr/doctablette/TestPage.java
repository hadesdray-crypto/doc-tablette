package fr.doctablette;

/** Page de test (écran, tactile, son) ouverte dans le navigateur de la tablette. */
final class TestPage {
    private TestPage() {
    }

    static final String HTML = "<!doctype html><html lang=fr><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Test de la tablette</title>"
            + "<style>body{margin:0;font:20px sans-serif;background:#111;color:#eee;text-align:center}"
            + "button{font-size:22px;padding:18px 24px;margin:8px;border:0;border-radius:12px;background:#35c2a1;color:#032}"
            + "#f{position:fixed;left:0;top:0;right:0;bottom:0;display:none}"
            + "canvas{display:none;touch-action:none;width:96vw;height:60vh;background:#222;margin:auto}</style>"
            + "<h2>Test de la tablette</h2>"
            + "<button onclick=px()>Écran : pixels morts</button>"
            + "<button onclick=td()>Tactile : dessiner</button>"
            + "<button onclick=sn()>Son</button>"
            + "<div id=f onclick=nx()></div><canvas id=c></canvas><p id=m></p>"
            + "<script>var cs=['#000','#fff','#f00','#0f0','#00f'],i=0,f=document.getElementById('f'),"
            + "c=document.getElementById('c'),m=document.getElementById('m');"
            + "function px(){i=0;f.style.background=cs[0];f.style.display='block';"
            + "m.textContent='Touchez l\\u2019écran pour changer de couleur. Cherchez un point qui reste différent.'}"
            + "function nx(){i++;if(i>=cs.length){f.style.display='none';"
            + "m.textContent='Test écran terminé. Un point resté d\\u2019une autre couleur = pixel mort.';return}"
            + "f.style.background=cs[i]}"
            + "function td(){c.style.display='block';c.width=c.offsetWidth;c.height=c.offsetHeight;"
            + "var x=c.getContext('2d');x.fillStyle='#444';for(var a=0;a<=8;a++){x.fillRect(a*c.width/8,0,2,c.height)}"
            + "for(var b=0;b<=6;b++){x.fillRect(0,b*c.height/6,c.width,2)}"
            + "m.textContent='Passez le doigt partout, avec 1 puis plusieurs doigts. Chaque zone touchée doit se colorer.';"
            + "c.onpointermove=function(e){if(e.pressure>0||e.buttons){var r=c.getBoundingClientRect();"
            + "x.fillStyle='#35c2a1';x.fillRect(e.clientX-r.left-10,e.clientY-r.top-10,20,20)}}}"
            + "function sn(){var A=window.AudioContext||window.webkitAudioContext,a=new A();"
            + "[[440,0],[880,.7],[220,1.4]].forEach(function(p){var o=a.createOscillator();o.frequency.value=p[0];"
            + "o.connect(a.destination);o.start(a.currentTime+p[1]);o.stop(a.currentTime+p[1]+.5)});"
            + "m.textContent='Vous devez entendre trois sons : moyen, aigu puis grave. Réglez le volume si besoin.'}"
            + "</script></html>";
}
