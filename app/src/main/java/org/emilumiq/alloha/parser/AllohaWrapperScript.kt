package org.emilumiq.alloha.parser

internal const val BRIDGE_NAME = "AndroidBridge"

internal fun wrapperHtml(iframeUrl: String): String = """
    <html><body style="margin:0;background:black">
    <iframe id="alloha" src="${iframeUrl.escapeHtml()}" width="100%" height="100%" frameborder="0" allowfullscreen></iframe>
    <script>
    try {
      Object.defineProperty(document, 'visibilityState', {get:function(){return 'visible'}});
      Object.defineProperty(document, 'hidden', {get:function(){return false}});
    } catch(e) {}
    var bnsi = null, headers = {}, done = false;
    function allohaInstall(frame) {
      var w, href, xhrProtoOpen;
      try { w = frame.contentWindow; } catch(e) { return false; }
      if(!w) return false;
      try { href = w.location.href; } catch(e) { return false; }
      if(!href || href === 'about:blank') return false;
      try { xhrProtoOpen = w.XMLHttpRequest.prototype.open; } catch(e) { return false; }
      if(!xhrProtoOpen) return false;
      if(xhrProtoOpen.__alloha) return true;
      try {
        try {
          Object.defineProperty(w.document, 'visibilityState', {get:function(){return 'visible'}});
          Object.defineProperty(w.document, 'hidden', {get:function(){return false}});
        } catch(e) {}
        var pushTimer = null;
        function put(k,v) {
          if(!k || !v) return;
          headers[String(k).toLowerCase()] = String(v);
          if(done) {
            if(pushTimer) clearTimeout(pushTimer);
            pushTimer = setTimeout(function(){ AndroidBridge.onStreamHeaders(JSON.stringify(headers)); }, 40);
          }
        }
        function ready() {
          if(done || !bnsi || !headers['authorizations'] || !headers['accepts-controls']) return;
          done = true;
          AndroidBridge.onReady(bnsi, JSON.stringify(headers));
        }
        put('origin', w.location.origin); put('referer', w.location.origin + '/');
        put('user-agent', w.navigator.userAgent); put('accept', '*/*');
        put('sec-fetch-dest', 'empty'); put('sec-fetch-mode', 'cors'); put('sec-fetch-site', 'cross-site');

        var lastMasterUrl = null;
        function isCdnMaster(url) {
          return !!url && url.indexOf('http') === 0 && url.indexOf('master.m3u8') !== -1;
        }
        function reportMaster(url) {
          if(!done || !url) return;
          if(!isCdnMaster(url)) return;
          if(url === lastMasterUrl) return;
          lastMasterUrl = url;
          AndroidBridge.onM3u8Refreshed(url, JSON.stringify(headers));
        }
        var primaryHost = null, fallbackHost = null, fallbackMasterUrl = null;
        function extractCdnHosts() {
          if(primaryHost || !bnsi) return;
          try {
            var data = JSON.parse(bnsi), sources = data.hlsSource;
            if(!sources || !sources[0] || !sources[0].quality) return;
            var quality = sources[0].quality, key = Object.keys(quality)[0];
            var urls = quality[key].split(' or ');
            if(urls.length < 2) return;
            var primary = urls[0].match(/https?:\/\/([^\/]+)/);
            var fallback = urls[1].trim().match(/https?:\/\/([^\/]+)/);
            if(primary) primaryHost = primary[1];
            if(fallback) {
              fallbackHost = fallback[1];
              fallbackMasterUrl = urls[1].trim();
            }
            AndroidBridge.onLog('CDN candidates captured');
          } catch(e) { AndroidBridge.onLog('CDN candidate parse failed'); }
        }

        var open = w.XMLHttpRequest.prototype.open;
        w.XMLHttpRequest.prototype.open = function(method,url) {
          this.__allohaUrl = url;
          this.addEventListener('load', function() {
            var url = this.responseURL || this.__allohaUrl || '';
            if(url.indexOf('/bnsi/') !== -1) {
              bnsi = this.responseText;
              extractCdnHosts();
              ready();
            }
            reportMaster(url);
          });
          this.addEventListener('loadend', function() {
            reportMaster(this.__allohaUrl || this.responseURL || '');
          });
          return open.apply(this, arguments);
        };
        w.XMLHttpRequest.prototype.open.__alloha = true;
        var setHeader = w.XMLHttpRequest.prototype.setRequestHeader;
        w.XMLHttpRequest.prototype.setRequestHeader = function(k,v) {
          put(k,v); ready(); return setHeader.apply(this, arguments);
        };
        var fetch = w.fetch;
        w.fetch = function(input,init) {
          try {
            var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
            if(init && init.headers) {
              if(typeof init.headers.forEach === 'function') init.headers.forEach(function(v,k){put(k,v)});
              else for(var k in init.headers) put(k,init.headers[k]);
            }
            if(url.indexOf('/bnsi/') !== -1) {
              return fetch.apply(this, arguments).then(function(response) {
                try {
                  response.clone().text().then(function(text) {
                    bnsi = text;
                    extractCdnHosts();
                    ready();
                  }).catch(function(){});
                } catch(e) {}
                return response;
              });
            }
            ready();
            extractCdnHosts();
            reportMaster(url);
            if(url && (url.indexOf('.m3u8') !== -1 || url.indexOf('.ts') !== -1 || url.indexOf('.m4s') !== -1) &&
                primaryHost && fallbackHost && url.indexOf(primaryHost) !== -1) {
              var fallbackUrl = url.indexOf('master.m3u8') !== -1 && fallbackMasterUrl
                ? fallbackMasterUrl : url.replace(primaryHost, fallbackHost);
              return fetch.apply(this, arguments).then(function(response) {
                if(response.status !== 403 && response.status !== 500 && response.status !== 503) return response;
                AndroidBridge.onLog('Browser CDN fallback after status=' + response.status);
                return fetch.call(w, fallbackUrl, init).then(function(fallbackResponse) {
                  if(fallbackResponse.ok) reportMaster(fallbackUrl);
                  return fallbackResponse;
                });
              });
            }
          } catch(e) {}
          return fetch.apply(this, arguments);
        };

        var OrigWS = w.WebSocket, send = OrigWS.prototype.send;
        var heartbeat = null, started = Date.now(), activeSocket = null, lastEdgeHash = null;
        function startHeartbeat(socket) {
          if(heartbeat) clearInterval(heartbeat);
          started = Date.now();
          heartbeat = setInterval(function() {
            if(!done || !socket || socket.readyState !== 1) return;
            try {
              send.call(socket, JSON.stringify({type:'playing',current_time:Math.floor((Date.now()-started)/1000),resolution:'1080',track_id:'1',speed:1,subtitle:0,ts:Date.now()}));
              AndroidBridge.onLog('heartbeat sent');
            } catch(e) { AndroidBridge.onLog('heartbeat failed'); }
          }, 25000);
        }
        function hookSocket(socket) {
          if(!socket || socket.__allohaHooked) return socket;
          socket.__allohaHooked = true;
          activeSocket = socket;
          started = Date.now();
          AndroidBridge.onLog('WebSocket hooked');
          socket.addEventListener('message', function(event) {
            try {
              var message = JSON.parse(event.data);
              if(message && message.type === 'config_update' && message.edge_hash && message.edge_hash !== lastEdgeHash) {
                lastEdgeHash = message.edge_hash;
                put('accepts-controls', message.edge_hash); ready();
                var ttl = message.ttl || 120;
                AndroidBridge.onLog('config_update ttl=' + ttl);
                AndroidBridge.onConfigUpdate(message.edge_hash, ttl, JSON.stringify(headers));
              }
            } catch(e) {}
          });
          socket.addEventListener('open', function() {
            AndroidBridge.onLog('WebSocket opened');
            startHeartbeat(socket);
          });
          socket.addEventListener('close', function(event){
            if(activeSocket === socket) {
              activeSocket = null;
              if(heartbeat) clearInterval(heartbeat);
            }
            var reason = event && event.reason ? String(event.reason).replace(/\s+/g, ' ').slice(0, 80) : '';
            AndroidBridge.onLog('WebSocket closed code=' + (event ? event.code : 0) +
              ' clean=' + (event ? event.wasClean : false) + ' reason=' + reason);
          });
          if(socket.readyState === 1) startHeartbeat(socket);
          return socket;
        }
        OrigWS.prototype.send = function(data) {
          hookSocket(this);
          return send.call(this,data);
        };
        w.WebSocket = function(url, protocols) {
          return hookSocket(protocols ? new OrigWS(url, protocols) : new OrigWS(url));
        };
        w.WebSocket.prototype = OrigWS.prototype;
        w.WebSocket.CONNECTING = OrigWS.CONNECTING;
        w.WebSocket.OPEN = OrigWS.OPEN;
        w.WebSocket.CLOSING = OrigWS.CLOSING;
        w.WebSocket.CLOSED = OrigWS.CLOSED;
        function muteMediaElement(el) {
          el.muted = true;
          el.volume = 0;
        }
        function muteAllMedia(doc, depth) {
          try {
            var media = doc.querySelectorAll('video, audio');
            for(var i=0;i<media.length;i++) muteMediaElement(media[i]);
            if(depth < 1) {
              var frames = doc.querySelectorAll('iframe');
              for(var j=0;j<frames.length;j++) {
                try {
                  var innerDoc = frames[j].contentDocument;
                  if(!innerDoc) continue;
                  if(!innerDoc.__allohaObserved) { innerDoc.__allohaObserved = true; observeMedia(innerDoc); }
                  muteAllMedia(innerDoc, depth + 1);
                } catch(e) {}
              }
            }
          } catch(e) {}
        }
        function observeMedia(doc) {
          try {
            var observer = new MutationObserver(function(mutations) {
              for(var m=0;m<mutations.length;m++) {
                var added = mutations[m].addedNodes;
                for(var n=0;n<added.length;n++) {
                  var node = added[n];
                  if(node.nodeType !== 1) continue;
                  if(node.tagName === 'VIDEO' || node.tagName === 'AUDIO') muteMediaElement(node);
                  if(node.querySelectorAll) {
                    var inner = node.querySelectorAll('video, audio');
                    for(var k=0;k<inner.length;k++) muteMediaElement(inner[k]);
                  }
                }
              }
            });
            observer.observe(doc.documentElement || doc, {childList:true, subtree:true});
          } catch(e) {}
        }
        observeMedia(w.document);
        var errorReported = false;
        var unavailablePattern = /озвучка\s*недоступна/i;
        var tickTimer = null;
        var fastPoll = true;
        function tick() {
          if(done && fastPoll) {
            fastPoll = false;
            clearInterval(tickTimer);
            tickTimer = setInterval(tick, 1500);
          }
          if(!done && !errorReported) {
            try {
              var text = w.document.body ? w.document.body.textContent : '';
              if(text && unavailablePattern.test(text)) {
                errorReported = true;
                AndroidBridge.onDubbingUnavailable();
                return;
              }
            } catch(e) {}
          }
          var root = w.document.querySelector('.allplay');
          if(!root || !root.classList.contains('allplay--playing')) {
            var button = w.document.querySelector('button.allplay__controls__item.allplay__control');
            if(button) button.click();
          }
          var video = w.document.querySelector('video');
          if(video) { video.muted = true; if(video.paused) video.play().catch(function(){}); }
          muteAllMedia(w.document, 0);
        }
        tickTimer = setInterval(tick, 300);
        tick();
        return true;
      } catch(e) { AndroidBridge.onLog('wrapper install failed: ' + e); return false; }
    }
    var allohaFrame = document.getElementById('alloha');
    allohaFrame.onload = function() { allohaInstall(allohaFrame); };
    var installPoll = setInterval(function() {
      if(allohaInstall(allohaFrame)) clearInterval(installPoll);
    }, 20);
    setTimeout(function() { clearInterval(installPoll); }, 8000);
    allohaInstall(allohaFrame);
    </script></body></html>
""".trimIndent()

private fun String.escapeHtml(): String = replace("&", "&amp;").replace("\"", "&quot;")
