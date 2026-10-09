console.log('Hangfire custom JS injected successfully.');

function initCustomHangfire() {
    setTimeout(() => {
        const navbar = document.querySelector('.navbar-nav');
        if (navbar) {
            const li = document.createElement('li');
            const a = document.createElement('a');
            a.href = '#';
            a.innerText = 'Custom Action';
            a.onclick = function (e) {
                e.preventDefault();
                alert('Custom action clicked!');
            };
            li.appendChild(a);
            navbar.appendChild(li);
        }
    }, 1000);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCustomHangfire);
} else {
    initCustomHangfire();
}