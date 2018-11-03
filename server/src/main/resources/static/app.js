"use strict";

// Define your client-side logic here.
$('form').submit(function(e) {
    var $form = $(this);
    var url = $form.attr('action');
    console.log("hello");
    console.log("form data [" + $form.serialize() + "]");
    console.log( toJSONString(this));
    $.ajax({
           type: 'POST',
           url: url,
           data: $form.serialize(),
           success: function(data) {
               $('div.alert').remove();
               $form.after('<br><div class="alert alert-success" role="alert">' + data + '</div>');
           },
           error: function(data) {
               $('div.alert').remove();
               $form.after('<br><div class="alert alert-danger" role="alert">Invalid input!</div>');
           }
         });

    e.preventDefault();
});

function toJSONString( form ) {
    var obj = {};
    var elements = form.querySelectorAll( "input" );
    for( var i = 0; i < elements.length; ++i ) {
        var element = elements[i];
        var name = element.name;
        var value = element.value;

        if( name ) {
            obj[ name ] = value;
        }
    }

    return JSON.stringify( obj );
}
