document.addEventListener("DOMContentLoaded", function(){
    //......
    
    function htmlToElement(html) {
        var template = document.createElement('template');
        html = html.trim(); // Never return a text node of whitespace as the result
        template.innerHTML = html;
        return template.content.firstChild;
    }
    
    var url = new URL('/news', window.location.href);
    url.protocol = url.protocol.replace('http', 'ws');

    let socket = new WebSocket(url.href);

    // socket.onopen = function(e) {
      // alert("[open] Connection established");
      // alert("Sending to server");
      // socket.send("My name is John");
    // };

    socket.onmessage = function(event) {
      let e = htmlToElement(event.data);
      document.body.insertBefore(e, document.body.firstChild);
    };

    socket.onclose = function(event) {
        console.log(event)
      if (event.wasClean) {
        alert(`[close] Connection closed cleanly, code=${event.code} reason=${event.reason}`);
      } else {
        // e.g. server process killed or network down
        // event.code is usually 1006 in this case
        alert('[close] Connection died');
      }
    };

    socket.onerror = function(error) {
        console.log(error)
      alert(`[error] ${error.message}`);
    };
});