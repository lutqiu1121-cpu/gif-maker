/*
 * 原生壳注入脚本（由 MainActivity 在 onPageFinished 时 evaluateJavascript 执行）。
 *
 * 网页是原封不动的单文件版本，它把生成好的 GIF 挂在 <a id="dl" download href="blob:..."> 上。
 * WebView 对 blob: 链接点击既不下载也不提示，所以这里接管这一次点击：
 * 把 blob 读成字节，分块交给原生桥，落到系统相册 Pictures/GifMaker/。
 */
(function () {
  if (window.__gifNativeShim) return;
  var B = window.AndroidBridge;
  if (!B || typeof B.beginGif !== 'function') return;
  window.__gifNativeShim = true;

  /* Uint8Array -> base64，分块 String.fromCharCode 避免爆栈 */
  function toB64(u8) {
    var s = '';
    var CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    }
    return btoa(s);
  }

  /* 48KB 一组：base64 后 64KB 字符，单次跨 JNI 不长 */
  var STEP = 48 * 1024;

  function saveBlob(blob, name) {
    var fr = new FileReader();
    fr.onerror = function () { B.toast('读取 GIF 数据失败，请重新生成'); };
    fr.onload = function () {
      try {
        var u8 = new Uint8Array(fr.result);
        if (!u8.length) { B.toast('GIF 数据为空'); return; }
        B.beginGif(name, u8.length);
        for (var i = 0; i < u8.length; i += STEP) {
          B.appendGif(toB64(u8.subarray(i, Math.min(i + STEP, u8.length))));
        }
        B.endGif();
      } catch (e) {
        B.toast('保存失败：' + (e && e.message ? e.message : e));
      }
    };
    fr.readAsArrayBuffer(blob);
  }

  /* 接管「下载 GIF」：blob 链接改走原生保存，data: 链接也一并兼容 */
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    var a = (t && t.closest) ? t.closest('#dl') : null;
    if (!a || !a.href) return;
    ev.preventDefault();
    ev.stopPropagation();
    var name = a.getAttribute('download') || '动图.gif';
    fetch(a.href)
      .then(function (r) { return r.blob(); })
      .then(function (b) { saveBlob(b, name); })
      .catch(function () { B.toast('读取 GIF 失败，可长按预览图另存'); });
  }, true);

  /* 按钮统一补一点触觉反馈，手感更接近原生 */
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (t && t.closest && t.closest('button, a.btn, .drop')) {
      try { B.tap(); } catch (e) { /* 忽略 */ }
    }
  }, true);
})();
