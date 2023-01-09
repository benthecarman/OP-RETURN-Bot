function queryAPI(rHash) {
    if (rHash) {
        fetch('https://opreturnbot.com/api/status/' + rHash)
            .then(async response => {
                const text = await response.text();
                if (response.ok) {
                    // Navigate to the URL on success
                    if (text) {
                        window.location.href = 'https://opreturnbot.com/success?txId=' + text;
                    }
                } else if (response.status === 400) {
                    // Handle the 400 error
                    console.error('Bad Request: ' + text);
                } else {
                    // Handle other errors
                    console.error('Error:', response.status);
                }
            })
            .catch(error => {
                // Handle any errors
                console.error(error);
            });
    } else {
        console.error('No rHash provided');
    }
}
